package com.example.mcpoauthdemo.oauth;

import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.APPROVED;
import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.COMPLETE;
import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.CREATED;
import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.DENIED;
import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.EXCHANGING;
import static com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus.FAILED;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.OAuthModels.FeishuGrant;
import com.example.mcpoauthdemo.oauth.OAuthModels.IssuedMcpToken;
import com.example.mcpoauthdemo.oauth.OAuthModels.LoginFlow;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpAuthContext;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpCode;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpRefreshToken;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpToken;
import com.example.mcpoauthdemo.oauth.OAuthModels.OAuthClient;
import com.example.mcpoauthdemo.security.CryptoSupport;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InMemoryOAuthStore {

    private final Map<String, OAuthClient> clients = new HashMap<>();
    private final Map<String, LoginFlow> flows = new HashMap<>();
    private final Map<String, String> flowIdsByFeishuStateHash = new HashMap<>();
    private final Map<String, FeishuGrant> grants = new HashMap<>();
    private final Map<String, McpCode> codes = new HashMap<>();
    private final Map<String, McpToken> tokens = new HashMap<>();
    private final Map<String, McpRefreshToken> refreshTokens = new HashMap<>();

    private final CryptoSupport crypto;
    private final DemoProperties properties;
    private final Clock clock;

    @Autowired
    public InMemoryOAuthStore(CryptoSupport crypto, DemoProperties properties) {
        this(crypto, properties, Clock.systemUTC());
    }

    InMemoryOAuthStore(CryptoSupport crypto, DemoProperties properties, Clock clock) {
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized void saveClient(OAuthClient client) {
        clients.put(client.clientId(), client);
    }

    public synchronized Optional<OAuthClient> findClient(String clientId) {
        return Optional.ofNullable(clients.get(clientId));
    }

    public synchronized void createFlow(LoginFlow flow) {
        flows.put(flow.flowId(), flow);
    }

    public synchronized LoginFlow getActiveFlow(String flowId) {
        LoginFlow flow = flows.get(flowId);
        if (flow == null || !flow.expiresAt().isAfter(clock.instant())) {
            throw new OAuthException("invalid_request", "Authorization flow is missing or expired");
        }
        return flow;
    }

    public synchronized LoginFlow approveFlow(
        String flowId,
        String browserId,
        String csrf,
        String feishuState,
        String encryptedVerifier
    ) {
        LoginFlow flow = validateBrowserFlow(flowId, browserId, csrf);
        if (flow.status() != CREATED) {
            throw new OAuthException("invalid_request", "Authorization flow was already handled");
        }
        String stateHash = crypto.sha256(feishuState);
        LoginFlow approved = flow.approved(stateHash, encryptedVerifier);
        flows.put(flowId, approved);
        flowIdsByFeishuStateHash.put(stateHash, flowId);
        return approved;
    }

    public synchronized LoginFlow denyFlow(String flowId, String browserId, String csrf) {
        LoginFlow flow = validateBrowserFlow(flowId, browserId, csrf);
        if (flow.status() != CREATED) {
            throw new OAuthException("invalid_request", "Authorization flow was already handled");
        }
        LoginFlow denied = flow.withStatus(DENIED);
        flows.put(flowId, denied);
        return denied;
    }

    public synchronized LoginFlow claimFeishuCallback(String rawState, String browserId) {
        if (rawState == null || browserId == null) {
            throw new OAuthException("invalid_request", "Missing state or browser cookie");
        }
        String flowId = flowIdsByFeishuStateHash.get(crypto.sha256(rawState));
        LoginFlow flow = flowId == null ? null : flows.get(flowId);
        if (flow == null || !flow.expiresAt().isAfter(clock.instant())) {
            throw new OAuthException("invalid_request", "Authorization flow is missing or expired");
        }
        if (!crypto.constantTimeEquals(crypto.sha256(browserId), flow.browserHash())) {
            throw new OAuthException("access_denied", "Browser binding does not match");
        }
        if (flow.status() != APPROVED) {
            throw new OAuthException("invalid_request", "Feishu callback was already handled");
        }
        LoginFlow claimed = flow.withStatus(EXCHANGING);
        flows.put(flowId, claimed);
        return claimed;
    }

    public synchronized void completeFlow(LoginFlow claimed, FeishuGrant grant, McpCode code) {
        LoginFlow current = flows.get(claimed.flowId());
        if (current == null || current.status() != EXCHANGING) {
            throw new OAuthException("server_error", "Authorization flow cannot be completed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        grants.put(grant.grantId(), grant);
        codes.put(code.codeHash(), code);
        flows.put(claimed.flowId(), current.withStatus(COMPLETE));
    }

    public synchronized void failFlow(String flowId) {
        LoginFlow current = flows.get(flowId);
        if (current != null) {
            flows.put(flowId, current.withStatus(FAILED));
        }
    }

    public synchronized IssuedMcpToken exchangeCode(
        OAuthClient client,
        String rawCode,
        String verifier,
        String resource,
        String redirectUri
    ) {
        Instant now = clock.instant();
        String codeHash = crypto.sha256(rawCode == null ? "" : rawCode);
        McpCode code = codes.get(codeHash);
        if (code == null || code.usedAt() != null || !code.expiresAt().isAfter(now)) {
            throw new OAuthException("invalid_grant", "Authorization code is invalid, expired, or already used");
        }
        if (!code.clientId().equals(client.clientId())) {
            throw new OAuthException("invalid_grant", "Authorization code belongs to another client");
        }
        if (!code.resource().equals(resource) || !properties.resourceUrl().equals(resource)) {
            throw new OAuthException("invalid_target", "Resource does not match the authorization request");
        }
        if (redirectUri != null && !redirectUri.equals(code.redirectUri())) {
            throw new OAuthException("invalid_grant", "redirect_uri does not match the authorization request");
        }
        if (!crypto.isValidPkceValue(verifier)
            || !crypto.constantTimeEquals(crypto.s256(verifier), code.challenge())) {
            throw new OAuthException("invalid_grant", "PKCE verification failed");
        }

        FeishuGrant grant = grants.get(code.grantId());
        if (grant == null || !grant.clientId().equals(client.clientId()) || grant.revokedAt() != null
            || !grant.accessTokenExpiresAt().isAfter(now)) {
            throw new OAuthException("invalid_grant", "Feishu grant is no longer active");
        }

        codes.put(codeHash, code.used(now));
        return issueTokenPair(client, grant, code.resource(), code.scope(), now,
            client.grantTypes().contains("refresh_token"), null);
    }

    public synchronized IssuedMcpToken refreshToken(
        OAuthClient client,
        String rawRefreshToken,
        String resource,
        String requestedScope
    ) {
        Instant now = clock.instant();
        String tokenHash = crypto.sha256(rawRefreshToken == null ? "" : rawRefreshToken);
        McpRefreshToken refreshToken = refreshTokens.get(tokenHash);
        if (refreshToken != null && refreshToken.usedAt() != null) {
            revokeTokenFamily(refreshToken.grantId(), now);
            throw new OAuthException("invalid_grant", "Refresh token is invalid, expired, or already used");
        }
        if (refreshToken == null || refreshToken.revokedAt() != null || !refreshToken.expiresAt().isAfter(now)) {
            throw new OAuthException("invalid_grant", "Refresh token is invalid, expired, or already used");
        }
        if (!refreshToken.clientId().equals(client.clientId())) {
            throw new OAuthException("invalid_grant", "Refresh token belongs to another client");
        }
        if (!refreshToken.resource().equals(resource) || !properties.resourceUrl().equals(resource)) {
            throw new OAuthException("invalid_target", "Resource does not match the refresh token");
        }
        String scope = refreshToken.scope();
        if (requestedScope != null) {
            Set<String> originalScopes = Set.of(refreshToken.scope().split("\\s+"));
            Set<String> requestedScopes = Set.of(requestedScope.split("\\s+"));
            if (!originalScopes.containsAll(requestedScopes)) {
                throw new OAuthException("invalid_scope", "Requested scope exceeds the original grant");
            }
            scope = requestedScope;
        }

        FeishuGrant grant = grants.get(refreshToken.grantId());
        if (grant == null || !grant.clientId().equals(client.clientId()) || grant.revokedAt() != null
            || !grant.accessTokenExpiresAt().isAfter(now)) {
            throw new OAuthException("invalid_grant", "Feishu grant is no longer active");
        }

        refreshTokens.put(tokenHash, refreshToken.used(now));
        return issueTokenPair(client, grant, refreshToken.resource(), scope, now, true, refreshToken.expiresAt());
    }

    public synchronized McpAuthContext authenticate(String rawToken) {
        Instant now = clock.instant();
        McpToken token = rawToken == null ? null : tokens.get(crypto.sha256(rawToken));
        if (token == null || token.revokedAt() != null || !token.expiresAt().isAfter(now)
            || !properties.resourceUrl().equals(token.resource())) {
            throw new OAuthException("invalid_token", "MCP access token is missing, expired, or invalid", HttpStatus.UNAUTHORIZED);
        }
        FeishuGrant grant = grants.get(token.grantId());
        if (grant == null || !grant.clientId().equals(token.clientId()) || grant.revokedAt() != null
            || !grant.accessTokenExpiresAt().isAfter(now)) {
            throw new OAuthException("invalid_token", "Feishu grant is missing, expired, or revoked", HttpStatus.UNAUTHORIZED);
        }
        return new McpAuthContext(token, grant);
    }

    private IssuedMcpToken issueTokenPair(
        OAuthClient client,
        FeishuGrant grant,
        String resource,
        String scope,
        Instant now,
        boolean includeRefreshToken,
        Instant refreshExpiryLimit
    ) {
        String rawToken = crypto.randomUrlSafe(32);
        Instant accessExpiresAt = now.plus(properties.getTokenTtl());
        if (grant.accessTokenExpiresAt().isBefore(accessExpiresAt)) {
            accessExpiresAt = grant.accessTokenExpiresAt();
        }
        McpToken token = new McpToken(crypto.sha256(rawToken), client.clientId(), grant.grantId(),
            resource, scope, accessExpiresAt, null);

        tokens.put(token.tokenHash(), token);
        String rawRefreshToken = null;
        if (includeRefreshToken) {
            rawRefreshToken = "mcp_rt_" + crypto.randomUrlSafe(32);
            Instant refreshExpiresAt = now.plus(properties.getRefreshTokenTtl());
            if (grant.accessTokenExpiresAt().isBefore(refreshExpiresAt)) {
                refreshExpiresAt = grant.accessTokenExpiresAt();
            }
            if (refreshExpiryLimit != null && refreshExpiryLimit.isBefore(refreshExpiresAt)) {
                refreshExpiresAt = refreshExpiryLimit;
            }
            McpRefreshToken refreshToken = new McpRefreshToken(crypto.sha256(rawRefreshToken), client.clientId(),
                grant.grantId(), resource, scope, refreshExpiresAt, null, null);
            refreshTokens.put(refreshToken.tokenHash(), refreshToken);
        }
        return new IssuedMcpToken(rawToken, rawRefreshToken, token,
            Math.max(0, accessExpiresAt.getEpochSecond() - now.getEpochSecond()));
    }

    private void revokeTokenFamily(String grantId, Instant now) {
        tokens.replaceAll((hash, token) -> token.grantId().equals(grantId) && token.revokedAt() == null
            ? token.revoked(now) : token);
        refreshTokens.replaceAll((hash, token) -> token.grantId().equals(grantId) && token.revokedAt() == null
            ? token.revoked(now) : token);
    }

    private LoginFlow validateBrowserFlow(String flowId, String browserId, String csrf) {
        LoginFlow flow = getActiveFlow(flowId);
        if (browserId == null || csrf == null
            || !crypto.constantTimeEquals(crypto.sha256(browserId), flow.browserHash())
            || !crypto.constantTimeEquals(crypto.sha256(csrf), flow.csrfHash())) {
            throw new OAuthException("access_denied", "Browser binding or CSRF token does not match");
        }
        return flow;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void removeExpiredEntries() {
        Instant now = clock.instant();
        flows.values().removeIf(flow -> !flow.expiresAt().isAfter(now));
        flowIdsByFeishuStateHash.entrySet().removeIf(entry -> !flows.containsKey(entry.getValue()));
        codes.values().removeIf(code -> !code.expiresAt().plusSeconds(60).isAfter(now));
        tokens.values().removeIf(token -> !token.expiresAt().isAfter(now));
        refreshTokens.values().removeIf(token -> !token.expiresAt().isAfter(now));
        grants.values().removeIf(grant -> grant.revokedAt() != null || !grant.accessTokenExpiresAt().isAfter(now));
    }
}
