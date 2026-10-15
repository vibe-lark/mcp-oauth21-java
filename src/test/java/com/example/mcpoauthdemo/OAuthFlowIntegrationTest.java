package com.example.mcpoauthdemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.mcpoauthdemo.oauth.InMemoryOAuthStore;
import com.example.mcpoauthdemo.security.CryptoSupport;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthFlowIntegrationTest {

    private static final Pattern HIDDEN_INPUT = Pattern.compile("name=\"%s\" value=\"([^\"]+)\"");

    @Autowired
    MockMvc mvc;

    @Autowired
    CryptoSupport crypto;

    @Autowired
    InMemoryOAuthStore store;

    @Autowired
    McpStatelessSyncServer mcpServer;

    @Test
    void completesPkceFlowAndPreventsCodeReplay() throws Exception {
        String verifier = "a".repeat(43);
        String challenge = crypto.s256(verifier);

        MvcResult registration = mvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "redirect_uris": ["http://127.0.0.1/client-callback"],
                      "client_name": "integration-test",
                      "grant_types": ["authorization_code", "refresh_token"],
                      "response_types": ["code"],
                      "token_endpoint_auth_method": "none"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_id").isString())
            .andReturn();
        String clientId = com.jayway.jsonpath.JsonPath.read(registration.getResponse().getContentAsString(), "$.client_id");

        MvcResult consent = mvc.perform(get("/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", "http://127.0.0.1/client-callback")
                .queryParam("response_type", "code")
                .queryParam("state", "client-state")
                .queryParam("scope", "mcp:read")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie browser = consent.getResponse().getCookie("oauth_browser_id");
        assertThat(browser).isNotNull();
        String html = consent.getResponse().getContentAsString();
        String flowId = hidden(html, "flow_id");
        String csrf = hidden(html, "csrf");

        MvcResult approval = mvc.perform(post("/authorize")
                .cookie(browser)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("flow_id", flowId)
                .param("csrf", csrf)
                .param("decision", "approve"))
            .andExpect(status().isFound())
            .andReturn();

        UriComponents mockAuthorize = components(approval.getResponse().getHeader(HttpHeaders.LOCATION));
        MvcResult mockCallback = mvc.perform(get(mockAuthorize.getPath())
                .queryParams(mockAuthorize.getQueryParams()))
            .andExpect(status().isFound())
            .andReturn();

        UriComponents callback = components(mockCallback.getResponse().getHeader(HttpHeaders.LOCATION));
        MvcResult clientRedirect = mvc.perform(get(callback.getPath())
                .cookie(browser)
                .queryParams(callback.getQueryParams()))
            .andExpect(status().isFound())
            .andReturn();
        UriComponents clientCallback = components(clientRedirect.getResponse().getHeader(HttpHeaders.LOCATION));
        String mcpCode = clientCallback.getQueryParams().getFirst("code");
        assertThat(mcpCode).isNotBlank();
        assertThat(clientCallback.getQueryParams().getFirst("state")).isEqualTo("client-state");

        mvc.perform(post("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", mcpCode)
                .param("code_verifier", "b".repeat(43)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

        MvcResult tokenResult = mvc.perform(post("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", mcpCode)
                .param("code_verifier", verifier))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.refresh_token").isString())
            .andReturn();
        String accessToken = com.jayway.jsonpath.JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.access_token");
        String refreshToken = com.jayway.jsonpath.JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.refresh_token");
        assertThat(store.authenticate(accessToken).grant().userName()).isEqualTo("小王");

        mvc.perform(post("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", mcpCode)
                .param("code_verifier", verifier))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

        MvcResult refreshResult = mvc.perform(post("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.access_token").isString())
            .andExpect(jsonPath("$.refresh_token").isString())
            .andReturn();
        String refreshedAccessToken = com.jayway.jsonpath.JsonPath.read(
            refreshResult.getResponse().getContentAsString(), "$.access_token");
        String rotatedRefreshToken = com.jayway.jsonpath.JsonPath.read(
            refreshResult.getResponse().getContentAsString(), "$.refresh_token");
        assertThat(refreshedAccessToken).isNotEqualTo(accessToken);
        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
        assertThat(store.authenticate(refreshedAccessToken).grant().userName()).isEqualTo("小王");

        mvc.perform(post("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

        assertThat(mcpServer.getServerInfo().name()).isEqualTo("mcp21-java-demo");
        assertThat(mcpServer.listTools()).extracting(tool -> tool.name()).containsExactly("whoami");
    }

    private String hidden(String html, String name) {
        Matcher matcher = Pattern.compile(HIDDEN_INPUT.pattern().formatted(Pattern.quote(name))).matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private UriComponents components(String location) {
        assertThat(location).isNotBlank();
        return UriComponentsBuilder.fromUri(URI.create(location)).build();
    }
}
