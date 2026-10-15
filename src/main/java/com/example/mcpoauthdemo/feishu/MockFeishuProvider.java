package com.example.mcpoauthdemo.feishu;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.example.mcpoauthdemo.oauth.OAuthException;
import com.example.mcpoauthdemo.security.CryptoSupport;
import org.springframework.stereotype.Component;

@Component
public class MockFeishuProvider {

    private final Map<String, MockCode> codes = new HashMap<>();
    private final CryptoSupport crypto;

    public MockFeishuProvider(CryptoSupport crypto) {
        this.crypto = crypto;
    }

    public synchronized String issueCode(String challenge) {
        String code = "mock_feishu_" + crypto.randomUrlSafe(24);
        codes.put(crypto.sha256(code), new MockCode(challenge, Instant.now().plusSeconds(120), false));
        return code;
    }

    public synchronized String exchange(String code, String verifier) {
        String key = crypto.sha256(code == null ? "" : code);
        MockCode saved = codes.get(key);
        if (saved == null || saved.used() || !saved.expiresAt().isAfter(Instant.now())
            || !crypto.isValidPkceValue(verifier)
            || !crypto.constantTimeEquals(saved.challenge(), crypto.s256(verifier))) {
            throw new OAuthException("feishu_exchange_failed", "Mock Feishu code or PKCE verifier is invalid");
        }
        codes.put(key, new MockCode(saved.challenge(), saved.expiresAt(), true));
        return "mock_uat_" + crypto.randomUrlSafe(32);
    }

    private record MockCode(String challenge, Instant expiresAt, boolean used) {
    }
}
