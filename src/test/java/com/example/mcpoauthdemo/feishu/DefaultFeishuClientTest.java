package com.example.mcpoauthdemo.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.OAuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
        client = new DefaultFeishuClient(properties, mock(MockFeishuProvider.class), new ObjectMapper());
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

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("X-Tt-Logid", List.of("test-request-id")), (k, v) -> true));
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
