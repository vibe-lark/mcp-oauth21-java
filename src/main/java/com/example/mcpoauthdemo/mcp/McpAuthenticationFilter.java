package com.example.mcpoauthdemo.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.mcpoauthdemo.config.DemoProperties;
import com.example.mcpoauthdemo.oauth.InMemoryOAuthStore;
import com.example.mcpoauthdemo.oauth.OAuthException;
import com.example.mcpoauthdemo.oauth.OAuthModels.McpAuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class McpAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ATTRIBUTE = McpAuthenticationFilter.class.getName() + ".auth";

    private final InMemoryOAuthStore store;
    private final DemoProperties properties;
    private final ObjectMapper objectMapper;

    public McpAuthenticationFilter(InMemoryOAuthStore store, DemoProperties properties, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (StringUtils.hasText(authorization) && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        }
        try {
            McpAuthContext context = store.authenticate(token);
            request.setAttribute(AUTH_ATTRIBUTE, context);
            filterChain.doFilter(request, response);
        }
        catch (OAuthException ex) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"" + properties.protectedResourceMetadataUrl() + "\"");
            objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "invalid_token",
                "error_description", ex.getMessage()
            ));
        }
    }
}
