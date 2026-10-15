package com.example.mcpoauthdemo.feishu;

import java.net.URI;
import java.time.Instant;

public interface FeishuClient {

    URI authorizationUri(String state, String codeChallenge);

    FeishuToken exchangeAuthorizationCode(String code, String codeVerifier);

    FeishuUser getUserInfo(String accessToken);

    record FeishuToken(String accessToken, Instant expiresAt, String scope) {
    }

    record FeishuUser(String name, String openId, String tenantKey) {
    }
}
