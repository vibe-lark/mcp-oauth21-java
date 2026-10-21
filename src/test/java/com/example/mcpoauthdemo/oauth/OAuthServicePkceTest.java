package com.example.mcpoauthdemo.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.feishu.FeishuClient;
import com.example.mcpoauthdemo.feishu.FeishuClient.FeishuToken;
import com.example.mcpoauthdemo.feishu.FeishuClient.FeishuUser;
import com.example.mcpoauthdemo.oauth.OAuthModels.FlowStatus;
import com.example.mcpoauthdemo.oauth.OAuthModels.LoginFlow;
import com.example.mcpoauthdemo.oauth.OAuthService.AuthorizationRequest;
import com.example.mcpoauthdemo.oauth.OAuthService.AuthorizationStart;
import com.example.mcpoauthdemo.oauth.OAuthService.RegistrationRequest;
import com.example.mcpoauthdemo.security.CryptoSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@ExtendWith(OutputCaptureExtension.class)
class OAuthServicePkceTest {

    private CryptoSupport crypto;
    private InMemoryOAuthStore store;
    private FeishuClient feishu;
    private OAuthService service;

    @BeforeEach
    void setUp() {
        crypto = new CryptoSupport();
        DemoProperties properties = new DemoProperties();
        store = new InMemoryOAuthStore(crypto, properties);
        feishu = mock(FeishuClient.class);
        service = new OAuthService(store, crypto, properties, feishu);
        when(feishu.authorizationUri(anyString(), anyString())).thenAnswer(invocation ->
            UriComponentsBuilder.fromUriString("https://accounts.feishu.cn/authorize")
                .queryParam("state", invocation.getArgument(0, String.class))
                .queryParam("code_challenge", invocation.getArgument(1, String.class))
                .queryParam("code_challenge_method", "S256")
                .build().toUri());
        when(feishu.exchangeAuthorizationCode(anyString(), anyString())).thenReturn(
            new FeishuToken("private-feishu-token", Instant.now().plusSeconds(3600), ""));
        when(feishu.getUserInfo("private-feishu-token")).thenReturn(
            new FeishuUser("test-user", "test-open-id", "test-tenant"));
    }

    @Test
    void savesTheRedirectChallengeAndUsesTheSameVerifierAtCallback(CapturedOutput output) {
        AuthorizationStart start = startAuthorization();
        URI redirect = service.decideAuthorization(start.flow().flowId(), start.csrf(), start.browserId(), true);
        UriComponents authorization = UriComponentsBuilder.fromUri(redirect).build();
        LoginFlow approved = store.getActiveFlow(start.flow().flowId());
        String verifier = crypto.decrypt(approved.feishuVerifierEncrypted());
        String state = authorization.getQueryParams().getFirst("state");
        String challenge = authorization.getQueryParams().getFirst("code_challenge");

        assertThat(approved.feishuChallenge()).isEqualTo(challenge).isEqualTo(crypto.s256(verifier));
        assertThat(approved.feishuVerifierEncrypted()).isNotEqualTo(verifier);
        assertThat(approved.feishuStateHash()).isEqualTo(crypto.sha256(state));
        assertThat(approved.challenge()).isNotEqualTo(approved.feishuChallenge());

        URI clientRedirect = service.completeFeishuCallback("private-feishu-code", state, null, start.browserId());

        verify(feishu).exchangeAuthorizationCode("private-feishu-code", verifier);
        assertThat(store.getActiveFlow(start.flow().flowId()).status()).isEqualTo(FlowStatus.COMPLETE);
        assertThat(UriComponentsBuilder.fromUri(clientRedirect).build().getQueryParams().getFirst("code"))
            .startsWith("mcp_code_");
        assertThat(output.getOut()).contains("Feishu PKCE authorize", "redirect_matches_generated=true",
                "Feishu PKCE callback", "local_match=true")
            .doesNotContain(verifier, challenge, state, "private-feishu-code", "private-feishu-token");
    }

    @Test
    void blocksAnEncryptedVerifierMismatchBeforeSendingTheCodeToFeishu(CapturedOutput output) {
        AuthorizationStart start = startAuthorization();
        URI redirect = service.decideAuthorization(start.flow().flowId(), start.csrf(), start.browserId(), true);
        String state = UriComponentsBuilder.fromUri(redirect).build().getQueryParams().getFirst("state");
        LoginFlow original = store.getActiveFlow(start.flow().flowId());
        String originalVerifier = crypto.decrypt(original.feishuVerifierEncrypted());
        String substitutedVerifier = "z".repeat(64);
        store.createFlow(original.approved(original.feishuStateHash(), crypto.encrypt(substitutedVerifier),
            original.feishuChallenge()));

        OAuthException error = catchThrowableOfType(OAuthException.class,
            () -> service.completeFeishuCallback("private-feishu-code", state, null, start.browserId()));

        assertThat(error.error()).isEqualTo("server_error");
        assertThat(error.status().value()).isEqualTo(500);
        verify(feishu, never()).exchangeAuthorizationCode(anyString(), anyString());
        verify(feishu, never()).getUserInfo(anyString());
        assertThat(store.getActiveFlow(start.flow().flowId()).status()).isEqualTo(FlowStatus.FAILED);
        assertThat(output.getOut()).contains("Feishu PKCE callback", "local_match=false")
            .doesNotContain(originalVerifier, substitutedVerifier, original.feishuChallenge(), state,
                "private-feishu-code", "private-feishu-token");
    }

    private AuthorizationStart startAuthorization() {
        String clientId = service.register(new RegistrationRequest(List.of("http://127.0.0.1/client-callback"),
            "test-client", List.of("authorization_code"), List.of("code"), "none")).client().clientId();
        return service.startAuthorization(new AuthorizationRequest(clientId, "http://127.0.0.1/client-callback",
            "code", "private-client-state", null, "mcp:read", crypto.s256("a".repeat(43)), "S256"));
    }
}
