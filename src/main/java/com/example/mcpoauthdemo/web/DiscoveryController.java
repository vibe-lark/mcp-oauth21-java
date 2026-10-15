package com.example.mcpoauthdemo.web;

import java.util.List;
import java.util.Map;

import com.example.mcpoauthdemo.config.DemoProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DiscoveryController {

    private final DemoProperties properties;

    public DiscoveryController(DemoProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
            "resource", properties.resourceUrl(),
            "authorization_servers", List.of(properties.baseUrl()),
            "scopes_supported", List.of("mcp:read")
        ));
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
        String baseUrl = properties.baseUrl();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
            "issuer", baseUrl,
            "registration_endpoint", baseUrl + "/register",
            "authorization_endpoint", baseUrl + "/authorize",
            "token_endpoint", baseUrl + "/token",
            "response_types_supported", List.of("code"),
            "grant_types_supported", List.of("authorization_code", "refresh_token"),
            "code_challenge_methods_supported", List.of("S256"),
            "token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic", "client_secret_post"),
            "scopes_supported", List.of("mcp:read")
        ));
    }
}
