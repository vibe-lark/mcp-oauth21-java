package com.example.mcpoauthdemo.web;

import java.net.URI;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.feishu.MockFeishuProvider;
import com.example.mcpoauthdemo.oauth.OAuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class MockFeishuController {

    private final DemoProperties properties;
    private final MockFeishuProvider provider;

    public MockFeishuController(DemoProperties properties, MockFeishuProvider provider) {
        this.properties = properties;
        this.provider = provider;
    }

    @GetMapping("/mock/feishu/authorize")
    public ResponseEntity<Void> authorize(
        @RequestParam(name = "redirect_uri") String redirectUri,
        @RequestParam String state,
        @RequestParam(name = "code_challenge") String challenge,
        @RequestParam(name = "code_challenge_method") String method
    ) {
        if (!properties.getFeishu().isMock()) {
            throw new OAuthException("not_found", "Mock Feishu is disabled", HttpStatus.NOT_FOUND);
        }
        if (!properties.callbackUrl().equals(redirectUri) || !"S256".equals(method)) {
            throw new OAuthException("invalid_request", "Invalid mock Feishu authorization request");
        }
        String code = provider.issueCode(challenge);
        URI location = UriComponentsBuilder.fromUriString(redirectUri)
            .queryParam("code", code).queryParam("state", state).build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
