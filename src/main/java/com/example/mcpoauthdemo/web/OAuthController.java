package com.example.mcpoauthdemo.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.OAuthModels.IssuedMcpToken;
import com.example.mcpoauthdemo.oauth.OAuthService;
import com.example.mcpoauthdemo.oauth.OAuthService.AuthorizationRequest;
import com.example.mcpoauthdemo.oauth.OAuthService.AuthorizationStart;
import com.example.mcpoauthdemo.oauth.OAuthService.RegistrationRequest;
import com.example.mcpoauthdemo.oauth.OAuthService.TokenRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping
public class OAuthController {

    private final OAuthService oauthService;
    private final DemoProperties properties;

    public OAuthController(OAuthService oauthService, DemoProperties properties) {
        this.oauthService = oauthService;
        this.properties = properties;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
        Map<String, Object> body = oauthService.register(request).response();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    @GetMapping(path = "/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(
        @RequestParam(name = "client_id") String clientId,
        @RequestParam(name = "redirect_uri") String redirectUri,
        @RequestParam(name = "response_type") String responseType,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "resource", required = false) String resource,
        @RequestParam(name = "scope", required = false) String scope,
        @RequestParam(name = "code_challenge") String codeChallenge,
        @RequestParam(name = "code_challenge_method") String codeChallengeMethod
    ) {
        AuthorizationStart start = oauthService.startAuthorization(new AuthorizationRequest(clientId, redirectUri,
            responseType, state, resource, scope, codeChallenge, codeChallengeMethod));
        ResponseCookie cookie = ResponseCookie.from(OAuthService.BROWSER_COOKIE, start.browserId())
            .httpOnly(true)
            .secure("https".equalsIgnoreCase(properties.getPublicBaseUrl().getScheme()))
            .sameSite("Lax")
            .path("/")
            .maxAge(properties.getFlowTtl())
            .build();
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(consentPage(start));
    }

    @PostMapping(path = "/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> authorizeDecision(
        @RequestParam(name = "flow_id") String flowId,
        @RequestParam String csrf,
        @RequestParam(defaultValue = "deny") String decision,
        @CookieValue(name = OAuthService.BROWSER_COOKIE, required = false) String browserId
    ) {
        URI location = oauthService.decideAuthorization(flowId, csrf, browserId, "approve".equals(decision));
        return noStoreRedirect(location);
    }

    @GetMapping("/callback/feishu")
    public ResponseEntity<Void> feishuCallback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @CookieValue(name = OAuthService.BROWSER_COOKIE, required = false) String browserId
    ) {
        return noStoreRedirect(oauthService.completeFeishuCallback(code, state, error, browserId));
    }

    @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
        @RequestParam(name = "grant_type") String grantType,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(name = "client_secret", required = false) String clientSecret,
        @RequestParam(required = false) String code,
        @RequestParam(name = "refresh_token", required = false) String refreshToken,
        @RequestParam(name = "code_verifier", required = false) String codeVerifier,
        @RequestParam(required = false) String resource,
        @RequestParam(name = "redirect_uri", required = false) String redirectUri,
        @RequestParam(required = false) String scope,
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        IssuedMcpToken issued = oauthService.exchangeToken(new TokenRequest(grantType, clientId, clientSecret,
            code, refreshToken, codeVerifier, resource, redirectUri, scope), authorization);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.rawToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", issued.expiresInSeconds());
        body.put("scope", issued.record().scope());
        if (issued.rawRefreshToken() != null) {
            body.put("refresh_token", issued.rawRefreshToken());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    private ResponseEntity<Void> noStoreRedirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache").build();
    }

    private String consentPage(AuthorizationStart start) {
        String client = HtmlUtils.htmlEscape(start.client().clientName());
        String scope = HtmlUtils.htmlEscape(start.flow().scope());
        String flowId = HtmlUtils.htmlEscape(start.flow().flowId());
        String csrf = HtmlUtils.htmlEscape(start.csrf());
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>授权 MCP 客户端</title>
              <style>
                body{font-family:system-ui,sans-serif;background:#f5f6f8;margin:0;padding:48px 20px;color:#1f2329}
                main{max-width:520px;margin:auto;background:white;border-radius:16px;padding:32px;box-shadow:0 12px 40px #1f23291a}
                h1{font-size:24px;margin-top:0}.scope{background:#f2f3f5;border-radius:8px;padding:12px;margin:24px 0}
                form{display:flex;gap:12px}button{border:0;border-radius:8px;padding:10px 20px;font-size:15px;cursor:pointer}
                .approve{background:#3370ff;color:white}.deny{background:#eff0f1;color:#1f2329}
              </style>
            </head>
            <body><main>
              <h1>确认授权</h1>
              <p><strong>%s</strong> 希望访问你的 MCP 服务。</p>
              <div class="scope">申请权限：%s</div>
              <form method="post" action="/authorize">
                <input type="hidden" name="flow_id" value="%s">
                <input type="hidden" name="csrf" value="%s">
                <button class="approve" name="decision" value="approve">同意并登录飞书</button>
                <button class="deny" name="decision" value="deny">拒绝</button>
              </form>
            </main></body></html>
            """.formatted(client, scope, flowId, csrf);
    }
}
