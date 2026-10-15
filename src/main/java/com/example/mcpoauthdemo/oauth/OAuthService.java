package com.example.mcpoauthdemo.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.feishu.FeishuClient;
import com.example.mcpoauthdemo.feishu.FeishuClient.FeishuToken;
import com.example.mcpoauthdemo.feishu.FeishuClient.FeishuUser;
import com.example.mcpoauthdemo.oauth.OAuthModels.FeishuGrant;
import com.example.mcpoauthdemo.oauth.OAuthModels.IssuedMcpToken;
import com.example.mcpoauthdemo.oauth.OAuthModels.LoginFlow;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpCode;
import com.example.mcpoauthdemo.oauth.OAuthModels.OAuthClient;
import com.example.mcpoauthdemo.security.CryptoSupport;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class OAuthService {

    public static final String BROWSER_COOKIE = "oauth_browser_id";
    private static final Set<String> AUTH_METHODS = Set.of("none", "client_secret_basic", "client_secret_post");
    private static final Set<String> SUPPORTED_GRANT_TYPES = Set.of("authorization_code", "refresh_token");
    private static final Set<String> ALLOWED_SCOPES = Set.of("mcp:read");

    private final InMemoryOAuthStore store;
    private final CryptoSupport crypto;
    private final DemoProperties properties;
    private final FeishuClient feishuClient;

    public OAuthService(
        InMemoryOAuthStore store,
        CryptoSupport crypto,
        DemoProperties properties,
        FeishuClient feishuClient
    ) {
        this.store = store;
        this.crypto = crypto;
        this.properties = properties;
        this.feishuClient = feishuClient;
    }

    public RegistrationResult register(RegistrationRequest request) {
        if (request == null || request.redirectUris() == null || request.redirectUris().isEmpty()) {
            throw new OAuthException("invalid_client_metadata", "redirect_uris must contain at least one URI");
        }
        Set<String> redirectUris = request.redirectUris().stream().map(this::validateRedirectUri)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> requestedGrantTypes = new LinkedHashSet<>(normalizeList(request.grantTypes(), "authorization_code"));
        if (!requestedGrantTypes.contains("authorization_code")
            || !SUPPORTED_GRANT_TYPES.containsAll(requestedGrantTypes)) {
            throw new OAuthException("invalid_client_metadata",
                "grant_types must contain authorization_code and may include refresh_token");
        }
        List<String> grantTypes = new ArrayList<>();
        grantTypes.add("authorization_code");
        if (requestedGrantTypes.contains("refresh_token")) {
            grantTypes.add("refresh_token");
        }
        List<String> responseTypes = normalizeList(request.responseTypes(), "code");
        if (!responseTypes.equals(List.of("code"))) {
            throw new OAuthException("invalid_client_metadata", "Only response_types=[code] is supported");
        }
        String method = StringUtils.hasText(request.tokenEndpointAuthMethod())
            ? request.tokenEndpointAuthMethod() : "client_secret_basic";
        if (!AUTH_METHODS.contains(method)) {
            throw new OAuthException("invalid_client_metadata", "Unsupported token_endpoint_auth_method");
        }

        String clientId = "mcp_" + crypto.randomUrlSafe(24);
        String secret = method.equals("none") ? null : crypto.randomUrlSafe(32);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("redirect_uris", redirectUris);
        metadata.put("client_name", StringUtils.hasText(request.clientName()) ? request.clientName() : "MCP client");
        metadata.put("grant_types", grantTypes);
        metadata.put("response_types", responseTypes);
        metadata.put("token_endpoint_auth_method", method);
        OAuthClient client = new OAuthClient(clientId, redirectUris, Set.copyOf(grantTypes),
            metadata.get("client_name").toString(), method,
            secret == null ? null : crypto.sha256(secret), Map.copyOf(metadata), Instant.now());
        store.saveClient(client);
        return new RegistrationResult(client, secret);
    }

    public AuthorizationStart startAuthorization(AuthorizationRequest request) {
        OAuthClient client = requireClient(request.clientId());
        if (!client.redirectUris().contains(request.redirectUri())) {
            throw new OAuthException("invalid_request", "redirect_uri is not registered for this client");
        }
        if (!"code".equals(request.responseType())) {
            throw new OAuthException("unsupported_response_type", "Only response_type=code is supported");
        }
        String resource = StringUtils.hasText(request.resource()) ? request.resource() : properties.resourceUrl();
        if (!properties.resourceUrl().equals(resource)) {
            throw new OAuthException("invalid_target", "resource must be " + properties.resourceUrl());
        }
        if (!"S256".equals(request.codeChallengeMethod()) || !crypto.isValidPkceValue(request.codeChallenge())) {
            throw new OAuthException("invalid_request", "A valid S256 PKCE code_challenge is required");
        }
        String scope = normalizeScope(request.scope());
        String flowId = "flow_" + crypto.randomUrlSafe(24);
        String browserId = crypto.randomUrlSafe(32);
        String csrf = crypto.randomUrlSafe(32);
        LoginFlow flow = new LoginFlow(flowId, client.clientId(), request.redirectUri(), request.state(),
            resource, scope, request.codeChallenge(), crypto.sha256(browserId), crypto.sha256(csrf),
            null, null, OAuthModels.FlowStatus.CREATED, Instant.now().plus(properties.getFlowTtl()));
        store.createFlow(flow);
        return new AuthorizationStart(flow, client, browserId, csrf);
    }

    public URI decideAuthorization(String flowId, String csrf, String browserId, boolean approved) {
        if (!approved) {
            LoginFlow denied = store.denyFlow(flowId, browserId, csrf);
            return clientRedirect(denied, Map.of("error", "access_denied"));
        }
        String feishuState = crypto.randomUrlSafe(32);
        String feishuVerifier = crypto.randomUrlSafe(48);
        store.approveFlow(flowId, browserId, csrf, feishuState, crypto.encrypt(feishuVerifier));
        return feishuClient.authorizationUri(feishuState, crypto.s256(feishuVerifier));
    }

    public URI completeFeishuCallback(String code, String state, String error, String browserId) {
        LoginFlow flow = store.claimFeishuCallback(state, browserId);
        if (StringUtils.hasText(error)) {
            store.failFlow(flow.flowId());
            return clientRedirect(flow, Map.of("error", "access_denied"));
        }
        if (!StringUtils.hasText(code)) {
            store.failFlow(flow.flowId());
            throw new OAuthException("invalid_request", "Feishu callback did not include a code");
        }
        try {
            FeishuToken feishuToken = feishuClient.exchangeAuthorizationCode(code,
                crypto.decrypt(flow.feishuVerifierEncrypted()));
            FeishuUser user = feishuClient.getUserInfo(feishuToken.accessToken());
            String grantId = "grant_" + crypto.randomUrlSafe(24);
            String rawMcpCode = "mcp_code_" + crypto.randomUrlSafe(32);
            FeishuGrant grant = new FeishuGrant(grantId, flow.clientId(), user.openId(), user.tenantKey(), user.name(),
                crypto.encrypt(feishuToken.accessToken()), feishuToken.expiresAt(), feishuToken.scope(), null);
            McpCode mcpCode = new McpCode(crypto.sha256(rawMcpCode), grantId, flow.clientId(), flow.redirectUri(),
                flow.resource(), flow.scope(), flow.challenge(), Instant.now().plus(properties.getCodeTtl()), null);
            store.completeFlow(flow, grant, mcpCode);
            return clientRedirect(flow, Map.of("code", rawMcpCode));
        }
        catch (RuntimeException ex) {
            store.failFlow(flow.flowId());
            throw ex;
        }
    }

    public IssuedMcpToken exchangeToken(TokenRequest request, String authorizationHeader) {
        ClientCredentials credentials = resolveClientCredentials(request, authorizationHeader);
        OAuthClient client = requireClient(credentials.clientId());
        authenticateClient(client, credentials);
        String resource = StringUtils.hasText(request.resource()) ? request.resource() : properties.resourceUrl();
        return switch (request.grantType()) {
            case "authorization_code" -> store.exchangeCode(client, request.code(), request.codeVerifier(), resource,
                request.redirectUri());
            case "refresh_token" -> {
                if (!client.grantTypes().contains("refresh_token")) {
                    throw new OAuthException("unauthorized_client", "Client is not registered for refresh_token");
                }
                if (!StringUtils.hasText(request.refreshToken())) {
                    throw new OAuthException("invalid_request", "refresh_token is required");
                }
                String requestedScope = StringUtils.hasText(request.scope()) ? normalizeScope(request.scope()) : null;
                yield store.refreshToken(client, request.refreshToken(), resource, requestedScope);
            }
            default -> throw new OAuthException("unsupported_grant_type",
                "Only authorization_code and refresh_token are supported");
        };
    }

    private ClientCredentials resolveClientCredentials(TokenRequest request, String authorizationHeader) {
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 1) {
                    throw new IllegalArgumentException();
                }
                return new ClientCredentials(formDecode(decoded.substring(0, colon)),
                    formDecode(decoded.substring(colon + 1)), "client_secret_basic");
            }
            catch (IllegalArgumentException ex) {
                throw invalidClient("Malformed HTTP Basic credentials");
            }
        }
        if (StringUtils.hasText(request.clientSecret())) {
            return new ClientCredentials(request.clientId(), request.clientSecret(), "client_secret_post");
        }
        return new ClientCredentials(request.clientId(), null, "none");
    }

    private void authenticateClient(OAuthClient client, ClientCredentials credentials) {
        if (!client.authMethod().equals(credentials.method())) {
            throw invalidClient("Client authentication method does not match registration");
        }
        if (!"none".equals(client.authMethod())
            && !crypto.constantTimeEquals(client.secretHash(), crypto.sha256(credentials.secret() == null ? "" : credentials.secret()))) {
            throw invalidClient("Client secret is invalid");
        }
    }

    private OAuthClient requireClient(String clientId) {
        if (!StringUtils.hasText(clientId)) {
            throw invalidClient("client_id is required");
        }
        return store.findClient(clientId).orElseThrow(() -> invalidClient("Unknown client_id"));
    }

    private OAuthException invalidClient(String message) {
        return new OAuthException("invalid_client", message, HttpStatus.UNAUTHORIZED);
    }

    private String normalizeScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "mcp:read";
        }
        List<String> values = List.of(scope.trim().split("\\s+"));
        if (values.stream().anyMatch(value -> !ALLOWED_SCOPES.contains(value))) {
            throw new OAuthException("invalid_scope", "Only mcp:read is supported");
        }
        return String.join(" ", new java.util.LinkedHashSet<>(values));
    }

    private String validateRedirectUri(String value) {
        try {
            URI uri = new URI(value);
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) || uri.getHost() == null
                || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        }
        catch (URISyntaxException | IllegalArgumentException ex) {
            throw new OAuthException("invalid_redirect_uri", "redirect_uri must be HTTPS or an HTTP loopback URI");
        }
    }

    private List<String> normalizeList(List<String> values, String defaultValue) {
        if (values == null || values.isEmpty()) {
            return List.of(defaultValue);
        }
        return List.copyOf(new ArrayList<>(values));
    }

    private URI clientRedirect(LoginFlow flow, Map<String, String> parameters) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(flow.redirectUri());
        parameters.forEach(builder::queryParam);
        if (StringUtils.hasText(flow.mcpState())) {
            builder.queryParam("state", flow.mcpState());
        }
        return builder.build().encode().toUri();
    }

    private String formDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public record RegistrationRequest(
        @JsonProperty("redirect_uris") List<String> redirectUris,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("grant_types") List<String> grantTypes,
        @JsonProperty("response_types") List<String> responseTypes,
        @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod
    ) {
    }

    public record RegistrationResult(OAuthClient client, String rawSecret) {
        public Map<String, Object> response() {
            Map<String, Object> response = new LinkedHashMap<>(client.metadata());
            response.put("client_id", client.clientId());
            response.put("client_id_issued_at", client.createdAt().getEpochSecond());
            if (rawSecret != null) {
                response.put("client_secret", rawSecret);
                response.put("client_secret_expires_at", 0);
            }
            return response;
        }
    }

    public record AuthorizationRequest(
        String clientId,
        String redirectUri,
        String responseType,
        String state,
        String resource,
        String scope,
        String codeChallenge,
        String codeChallengeMethod
    ) {
    }

    public record AuthorizationStart(LoginFlow flow, OAuthClient client, String browserId, String csrf) {
    }

    public record TokenRequest(
        String grantType,
        String clientId,
        String clientSecret,
        String code,
        String refreshToken,
        String codeVerifier,
        String resource,
        String redirectUri,
        String scope
    ) {
    }

    private record ClientCredentials(String clientId, String secret, String method) {
    }
}
