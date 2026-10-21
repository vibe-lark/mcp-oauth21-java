package com.example.mcpoauthdemo.feishu;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.OAuthException;
import com.example.mcpoauthdemo.security.CryptoSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DefaultFeishuClient implements FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultFeishuClient.class);
    private static final URI TOKEN_ENDPOINT = URI.create("https://open.feishu.cn/open-apis/authen/v2/oauth/token");
    private static final URI USER_INFO_ENDPOINT = URI.create("https://open.feishu.cn/open-apis/authen/v1/user_info");

    private final DemoProperties properties;
    private final MockFeishuProvider mockProvider;
    private final ObjectMapper objectMapper;
    private final CryptoSupport crypto;
    private final HttpClient httpClient;

    public DefaultFeishuClient(DemoProperties properties, MockFeishuProvider mockProvider, ObjectMapper objectMapper,
        CryptoSupport crypto) {
        this.properties = properties;
        this.mockProvider = mockProvider;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public URI authorizationUri(String state, String codeChallenge) {
        if (properties.getFeishu().isMock()) {
            return UriComponentsBuilder.fromUriString(properties.baseUrl() + "/mock/feishu/authorize")
                .queryParam("redirect_uri", properties.callbackUrl())
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
        }
        requireRealConfiguration();
        UriComponentsBuilder builder = UriComponentsBuilder
            .fromUriString("https://accounts.feishu.cn/open-apis/authen/v1/authorize")
            .queryParam("client_id", properties.getFeishu().getAppId())
            .queryParam("redirect_uri", properties.callbackUrl())
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("response_type", "code");
        if (StringUtils.hasText(properties.getFeishu().getScopes())) {
            builder.queryParam("scope", properties.getFeishu().getScopes());
        }
        return builder.build().encode().toUri();
    }

    @Override
    public FeishuToken exchangeAuthorizationCode(String code, String codeVerifier) {
        if (properties.getFeishu().isMock()) {
            String accessToken = mockProvider.exchange(code, codeVerifier);
            return new FeishuToken(accessToken, Instant.now().plusSeconds(3600), "contact:user.base:readonly");
        }
        requireRealConfiguration();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("grant_type", "authorization_code");
        payload.put("client_id", properties.getFeishu().getAppId());
        payload.put("client_secret", properties.getFeishu().getAppSecret());
        payload.put("code", code);
        payload.put("redirect_uri", properties.callbackUrl());
        payload.put("code_verifier", codeVerifier);
        String requestBody;
        String requestVerifier;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
            // Inspect the serialized request body without logging credentials.
            requestVerifier = objectMapper.readTree(requestBody).path("code_verifier").asText(null);
        }
        catch (JsonProcessingException ex) {
            throw new OAuthException("server_error", "Could not serialize Feishu token request",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
        boolean bodyMatch = crypto.constantTimeEquals(codeVerifier, requestVerifier);
        String computedChallenge = requestVerifier == null ? null : crypto.s256(requestVerifier);
        log.info("Feishu PKCE token_request code_fp={} challenge_fp={} computed_challenge={} verifier_length={} verifier_valid={} "
                + "body_match={} endpoint={}",
            crypto.fingerprint(code), crypto.fingerprint(computedChallenge), computedChallenge,
            requestVerifier == null ? 0 : requestVerifier.length(), crypto.isValidPkceValue(requestVerifier),
            bodyMatch, TOKEN_ENDPOINT);
        if (!bodyMatch) {
            throw new OAuthException("server_error", "Local Feishu token request failed PKCE consistency check",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }

        JsonNode json = send(HttpRequest.newBuilder(TOKEN_ENDPOINT)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build(), "Feishu token exchange", code, codeVerifier);
        String token = requiredText(json, "access_token", "Feishu token response");
        long expiresIn = json.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw upstreamError("Feishu token response did not include a valid expires_in");
        }
        return new FeishuToken(token, Instant.now().plusSeconds(expiresIn), json.path("scope").asText(""));
    }

    @Override
    public FeishuUser getUserInfo(String accessToken) {
        if (properties.getFeishu().isMock()) {
            if (!StringUtils.hasText(accessToken) || !accessToken.startsWith("mock_uat_")) {
                throw upstreamError("Mock Feishu access token is invalid");
            }
            return new FeishuUser(properties.getFeishu().getMockUserName(),
                properties.getFeishu().getMockOpenId(), properties.getFeishu().getMockTenantKey());
        }
        JsonNode json = send(HttpRequest.newBuilder(USER_INFO_ENDPOINT)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET().build(), "Feishu user info", accessToken);
        JsonNode data = json.path("data");
        return new FeishuUser(requiredText(data, "name", "Feishu user response"),
            requiredText(data, "open_id", "Feishu user response"), data.path("tenant_key").asText(""));
    }

    private JsonNode send(HttpRequest request, String operation, String... credentials) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = objectMapper.readTree(response.body());
            }
            catch (IOException ex) {
                // Do not include raw bodies or parser messages: they can contain credentials.
                json = null;
            }
            String details = errorDetails(response, json, credentials);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamError(operation + " returned HTTP " + response.statusCode() + details);
            }
            if (json == null || !json.isObject()) {
                throw upstreamError(operation + " returned an invalid JSON response" + details);
            }
            if (json.has("error") || (json.has("code") && json.path("code").asInt() != 0)) {
                throw upstreamError(operation + " failed" + details);
            }
            return json;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw upstreamError(operation + " was interrupted");
        }
        catch (IOException ex) {
            throw upstreamError(operation + " failed: " + ex.getClass().getSimpleName());
        }
    }

    private String errorDetails(HttpResponse<String> response, JsonNode json, String... credentials) {
        List<String> secrets = new ArrayList<>(List.of(credentials));
        secrets.add(properties.getFeishu().getAppSecret());
        collectResponseCredentials(json, secrets);
        Map<String, String> fields = new LinkedHashMap<>();
        if (json != null && json.isObject()) {
            for (String key : List.of("error", "error_description", "code", "msg", "message", "request_id", "log_id")) {
                if (json.hasNonNull(key) && json.get(key).isValueNode()) {
                    fields.put(key, json.get(key).asText());
                }
            }
        }
        response.headers().firstValue("X-Tt-Logid").ifPresent(value -> fields.put("x-tt-logid", value));
        String details = fields.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + redact(entry.getValue(), secrets))
            .collect(Collectors.joining(", "));
        return details.isEmpty() ? " (no structured error details)" : " (" + details + ")";
    }

    private void collectResponseCredentials(JsonNode json, List<String> secrets) {
        if (json == null) {
            return;
        }
        if (json.isObject()) {
            for (String key : List.of("client_secret", "app_secret", "access_token", "refresh_token",
                "user_access_token", "tenant_access_token", "app_access_token", "id_token", "code_verifier")) {
                if (json.hasNonNull(key) && json.get(key).isTextual()) {
                    secrets.add(json.get(key).asText());
                }
            }
        }
        json.forEach(child -> collectResponseCredentials(child, secrets));
    }

    private String redact(String value, List<String> secrets) {
        for (String secret : secrets) {
            if (StringUtils.hasText(secret)) {
                value = value.replace(secret, "[REDACTED]").replace(encode(secret), "[REDACTED]");
            }
        }
        value = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return value.length() > 512 ? value.substring(0, 512) + "…" : value;
    }

    private String requiredText(JsonNode node, String field, String source) {
        String value = node.path(field).asText("");
        if (!StringUtils.hasText(value)) {
            throw upstreamError(source + " did not include " + field);
        }
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void requireRealConfiguration() {
        if (!StringUtils.hasText(properties.getFeishu().getAppId())
            || !StringUtils.hasText(properties.getFeishu().getAppSecret())) {
            throw new OAuthException("server_error", "FEISHU_APP_ID and FEISHU_APP_SECRET are required when mock mode is disabled",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private OAuthException upstreamError(String message) {
        return new OAuthException("temporarily_unavailable", message, HttpStatus.BAD_GATEWAY);
    }
}
