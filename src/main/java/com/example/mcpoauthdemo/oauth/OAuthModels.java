package com.example.mcpoauthdemo.oauth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class OAuthModels {

    private OAuthModels() {
    }

    public enum FlowStatus {
        CREATED, APPROVED, EXCHANGING, COMPLETE, DENIED, FAILED
    }

    public record OAuthClient(
        String clientId,
        Set<String> redirectUris,
        Set<String> grantTypes,
        String clientName,
        String authMethod,
        String secretHash,
        Map<String, Object> metadata,
        Instant createdAt
    ) {
    }

    public record LoginFlow(
        String flowId,
        String clientId,
        String redirectUri,
        String mcpState,
        String resource,
        String scope,
        String challenge,
        String browserHash,
        String csrfHash,
        String feishuStateHash,
        String feishuVerifierEncrypted,
        FlowStatus status,
        Instant expiresAt
    ) {
        public LoginFlow approved(String stateHash, String verifierEncrypted) {
            return new LoginFlow(flowId, clientId, redirectUri, mcpState, resource, scope, challenge,
                browserHash, csrfHash, stateHash, verifierEncrypted, FlowStatus.APPROVED, expiresAt);
        }

        public LoginFlow withStatus(FlowStatus newStatus) {
            return new LoginFlow(flowId, clientId, redirectUri, mcpState, resource, scope, challenge,
                browserHash, csrfHash, feishuStateHash, feishuVerifierEncrypted, newStatus, expiresAt);
        }
    }

    public record FeishuGrant(
        String grantId,
        String clientId,
        String openId,
        String tenantKey,
        String userName,
        String accessTokenEncrypted,
        Instant accessTokenExpiresAt,
        String feishuScope,
        Instant revokedAt
    ) {
    }

    public record McpCode(
        String codeHash,
        String grantId,
        String clientId,
        String redirectUri,
        String resource,
        String scope,
        String challenge,
        Instant expiresAt,
        Instant usedAt
    ) {
        public McpCode used(Instant when) {
            return new McpCode(codeHash, grantId, clientId, redirectUri, resource, scope,
                challenge, expiresAt, when);
        }
    }

    public record McpToken(
        String tokenHash,
        String clientId,
        String grantId,
        String resource,
        String scope,
        Instant expiresAt,
        Instant revokedAt
    ) {
        public McpToken revoked(Instant when) {
            return new McpToken(tokenHash, clientId, grantId, resource, scope, expiresAt, when);
        }
    }

    public record McpRefreshToken(
        String tokenHash,
        String clientId,
        String grantId,
        String resource,
        String scope,
        Instant expiresAt,
        Instant usedAt,
        Instant revokedAt
    ) {
        public McpRefreshToken used(Instant when) {
            return new McpRefreshToken(tokenHash, clientId, grantId, resource, scope, expiresAt, when, revokedAt);
        }

        public McpRefreshToken revoked(Instant when) {
            return new McpRefreshToken(tokenHash, clientId, grantId, resource, scope, expiresAt, usedAt, when);
        }
    }

    public record McpAuthContext(McpToken token, FeishuGrant grant) {
        public boolean hasScope(String expected) {
            return Set.of(token.scope().split("\\s+")).contains(expected);
        }
    }

    public record IssuedMcpToken(
        String rawToken,
        String rawRefreshToken,
        McpToken record,
        long expiresInSeconds
    ) {
    }
}
