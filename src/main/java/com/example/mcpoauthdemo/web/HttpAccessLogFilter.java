package com.example.mcpoauthdemo.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.example.mcpoauthdemo.security.CryptoSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessLogFilter.class);
    private static final String FEISHU_AUTHORIZE = "https://accounts.feishu.cn/open-apis/authen/v1/authorize";
    private static final List<String> AUTHORIZATION_LOG_FIELDS = List.of("client_id", "redirect_uri",
        "response_type", "scope", "code_challenge", "code_challenge_method");

    private final CryptoSupport crypto;

    public HttpAccessLogFilter(CryptoSupport crypto) {
        this.crypto = crypto;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            logFeishuAuthorizationRedirect(request, response);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("HTTP {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                response.getStatus(), elapsedMillis);
        }
    }

    private void logFeishuAuthorizationRedirect(HttpServletRequest request, HttpServletResponse response) {
        if (!"POST".equals(request.getMethod()) || !"/authorize".equals(request.getRequestURI())
            || response.getStatus() != HttpServletResponse.SC_FOUND) {
            return;
        }
        String location = response.getHeader(HttpHeaders.LOCATION);
        if (location == null) {
            return;
        }
        try {
            var uri = UriComponentsBuilder.fromUriString(location).build();
            if (!"https".equals(uri.getScheme()) || !"accounts.feishu.cn".equals(uri.getHost())
                || !"/open-apis/authen/v1/authorize".equals(uri.getPath())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return;
            }
            var parameters = uri.getQueryParams();
            String challenge = parameters.getFirst("code_challenge");
            if (!List.of("S256").equals(parameters.get("code_challenge_method"))
                || parameters.get("code_challenge") == null || parameters.get("code_challenge").size() != 1
                || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
                // A plain challenge would expose the verifier. Never log it or the raw Location.
                log.warn("Feishu OAuth server_redirect parameters omitted: expected a single valid S256 challenge");
                return;
            }
            var safeLocation = UriComponentsBuilder.fromUriString(FEISHU_AUTHORIZE);
            for (String field : AUTHORIZATION_LOG_FIELDS) {
                List<String> values = parameters.get(field);
                if (values != null) {
                    safeLocation.queryParam(field, values);
                }
            }
            safeLocation.queryParam("state", "REDACTED");
            String state = parameters.getFirst("state");
            // Preserve URL encoding in logged fields, so encoded characters cannot create log lines.
            String safeUrl = safeLocation.build(true).toUriString();
            String flow = request.getParameter("flow_id");
            if (flow == null || !flow.matches("flow_[A-Za-z0-9_-]{1,128}")) {
                flow = "unknown";
            }
            log.info("Feishu OAuth server_redirect flow={} status={} state_fp={} location={}",
                flow, response.getStatus(), crypto.fingerprint(state == null ? null
                    : UriUtils.decode(state, StandardCharsets.UTF_8)), safeUrl);
        }
        catch (IllegalArgumentException ex) {
            // Diagnostic parsing must not break authorization or expose a malformed URL in an exception.
            log.warn("Feishu OAuth server_redirect parameters omitted: invalid Location");
        }
    }
}
