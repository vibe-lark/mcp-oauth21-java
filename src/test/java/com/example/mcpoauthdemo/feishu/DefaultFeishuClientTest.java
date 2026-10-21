package com.example.mcpoauthdemo.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.OAuthException;
import com.example.mcpoauthdemo.security.CryptoSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(OutputCaptureExtension.class)
class DefaultFeishuClientTest {
    private DefaultFeishuClient client;
    private HttpClient transport;

    @BeforeEach
    void setUp() {
        DemoProperties properties = new DemoProperties();
        properties.getFeishu().setMock(false);
        properties.getFeishu().setAppId("test-app");
        properties.getFeishu().setAppSecret("private-app-secret");
        properties.getFeishu().setScopes("");
        client = new DefaultFeishuClient(properties, mock(MockFeishuProvider.class), new ObjectMapper(),
            new CryptoSupport());
        transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
    }

    @Test
    void reportsHttp400DetailsWithoutCredentials() throws Exception {
        respond(400, """
            {"error":"invalid_grant","code":20025,
             "error_description":"Rejected private-code private-verifier private-app-secret private-token\\nretry",
             "access_token":"private-token","internal_debug":"do-not-print"}
            """);
        OAuthException error = catchThrowableOfType(OAuthException.class,
            () -> client.exchangeAuthorizationCode("private-code", "private-verifier"));
        assertThat(error.getMessage()).contains("HTTP 400", "invalid_grant", "code=20025", "[REDACTED]", "test-request-id")
            .doesNotContain("private-code", "private-verifier", "private-app-secret", "private-token", "do-not-print", "\n");
        assertThat(error.status().value()).isEqualTo(502);
    }

    @Test
    void doesNotExposeNonJsonErrorBodies() throws Exception {
        respond(400, "<html>private-app-secret upstream failure</html>");
        OAuthException error = catchThrowableOfType(OAuthException.class,
            () -> client.exchangeAuthorizationCode("private-code", "private-verifier"));
        assertThat(error.getMessage()).contains("HTTP 400", "test-request-id")
            .doesNotContain("private-app-secret", "<html>");
    }

    @Test
    void reportsApiErrorsEvenWhenHttpStatusIsSuccessful() throws Exception {
        respond(200, """
            {"code":99991663,"msg":"Invalid user token private-user-token"}
            """);
        OAuthException error = catchThrowableOfType(OAuthException.class,
            () -> client.getUserInfo("private-user-token"));
        assertThat(error.getMessage()).contains("code=99991663", "Invalid user token [REDACTED]")
            .doesNotContain("private-user-token");
    }

    @Test
    void preservesSuccessfulTokenResponsesAndEmptyAuthorizationScope() throws Exception {
        respond(200, """
            {"access_token":"private-token","expires_in":3600,"scope":""}
            """);
        assertThat(client.exchangeAuthorizationCode("private-code", "private-verifier").accessToken())
            .isEqualTo("private-token");
        assertThat(client.authorizationUri("test-state", "test-challenge").getQuery()).doesNotContain("scope=");
    }

    @Test
    void sendsV2JsonWithMatchingPkceAndLogsNoCredentials(CapturedOutput output)
        throws Exception {
        CryptoSupport crypto = new CryptoSupport();
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        String code = "private-code-with-special-characters+&=";
        URI authorizationUri = client.authorizationUri("private-state", challenge);
        Map<String, String> authorizationParameters = decodeForm(authorizationUri.getRawQuery());
        assertThat(authorizationParameters).containsEntry("code_challenge", challenge)
            .containsEntry("code_challenge_method", "S256")
            .containsEntry("response_type", "code");
        assertThat(authorizationParameters).doesNotContainKey("scope");

        respond(200, """
            {"access_token":"private-token","expires_in":3600,"scope":""}
            """);
        client.exchangeAuthorizationCode(code, verifier);

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = sent.getValue();
        HttpResponse.BodySubscriber<String> subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(ByteBuffer item) {
                subscriber.onNext(List.of(item));
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
        String rawBody = subscriber.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Map<String, String> tokenParameters = new ObjectMapper().readValue(rawBody, new TypeReference<>() {});

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo(URI.create("https://open.feishu.cn/open-apis/authen/v2/oauth/token"));
        assertThat(request.headers().firstValue("Content-Type"))
            .hasValue("application/json; charset=utf-8");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(tokenParameters).hasSize(6)
            .containsEntry("grant_type", "authorization_code")
            .containsEntry("client_id", "test-app")
            .containsEntry("client_secret", "private-app-secret")
            .containsEntry("code_verifier", verifier)
            .containsEntry("code", code);
        assertThat(crypto.s256(tokenParameters.get("code_verifier")))
            .isEqualTo(authorizationParameters.get("code_challenge"));
        assertThat(tokenParameters.get("redirect_uri"))
            .isEqualTo(authorizationParameters.get("redirect_uri"));
        assertThat(output.getOut()).contains("Feishu PKCE token_request", "body_match=true",
                "challenge_fp=" + crypto.fingerprint(challenge), "computed_challenge=" + challenge)
            .doesNotContain(verifier, code, "private-app-secret", "private-token", "private-state");
    }

    private static Map<String, String> decodeForm(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("X-Tt-Logid", List.of("test-request-id")), (k, v) -> true));
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
