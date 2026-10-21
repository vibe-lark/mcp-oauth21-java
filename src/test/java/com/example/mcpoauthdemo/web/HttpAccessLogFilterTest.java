package com.example.mcpoauthdemo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.example.mcpoauthdemo.security.CryptoSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class HttpAccessLogFilterTest {

    private static final String ENDPOINT = "https://accounts.feishu.cn/open-apis/authen/v1/authorize";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private final CryptoSupport crypto = new CryptoSupport();
    private final HttpAccessLogFilter filter = new HttpAccessLogFilter(crypto);

    @Test
    void logsTheFinalResponseLocationWithOnlySafeAuthorizationFields(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authorize");
        request.addParameter("flow_id", "flow_for_test");
        request.addHeader("Authorization", "Bearer private-header-token");
        request.setCookies(new Cookie("browser", "private-cookie"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Location", "https://stale.example/old-location");
        String location = ENDPOINT + "?client_id=response-app&redirect_uri=https%3A%2F%2Flocalhost%3A8080%2Fcallback%2Ffeishu"
            + "&response_type=code&scope=contact%3Auser.employee_id%3Areadonly%20offline_access"
            + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256&state=private-state"
            + "&code=private-code&code_verifier=private-verifier&client_secret=private-secret"
            + "&access_token=private-access-token&refresh_token=private-refresh-token&unknown=private-unknown";

        filter.doFilter(request, response, (req, res) -> {
            response.setStatus(302);
            response.setHeader("Location", location);
        });

        String logged = URLDecoder.decode(output.getAll(), StandardCharsets.UTF_8);
        assertThat(logged).contains("Feishu OAuth server_redirect", "flow=flow_for_test", "status=302",
                "state_fp=" + crypto.fingerprint("private-state"), "location=" + ENDPOINT,
                "client_id=response-app", "redirect_uri=https://localhost:8080/callback/feishu",
                "response_type=code", "scope=contact:user.employee_id:readonly offline_access",
                "code_challenge=" + CHALLENGE, "code_challenge_method=S256", "state=REDACTED")
            .doesNotContain("private-state", "private-code", "private-verifier", "private-secret",
                "private-access-token", "private-refresh-token", "private-unknown", "private-header-token",
                "private-cookie", "stale.example", "code_verifier=", "client_secret=", "access_token=",
                "refresh_token=", "unknown=");
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo(location);
    }

    @Test
    void doesNotLogAClientRedirectContainingAnAuthorizationCode(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authorize");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String location = "https://doubao.example/callback?code=private-mcp-code&state=private-client-state";

        filter.doFilter(request, response, (req, res) -> {
            response.setStatus(302);
            response.setHeader("Location", location);
        });

        assertThat(output.getAll()).doesNotContain("Feishu OAuth server_redirect", "private-mcp-code",
            "private-client-state", "doubao.example");
        assertThat(response.getHeader("Location")).isEqualTo(location);
    }

    @Test
    void doesNotExposeAPlainChallengeWhichWouldBeTheVerifier(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authorize");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String verifier = "private-plain-verifier-which-must-never-be-logged";
        String location = ENDPOINT + "?client_id=private-app&scope=private-scope&state=private-state"
            + "&code_challenge=" + verifier + "&code_challenge_method=plain";

        filter.doFilter(request, response, (req, res) -> {
            response.setStatus(302);
            response.setHeader("Location", location);
        });

        assertThat(output.getAll()).contains("WARN")
            .doesNotContain(verifier, "private-app", "private-scope", "private-state", "location=" + ENDPOINT);
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo(location);
    }
}
