package com.example.mcpoauthdemo.web;

import java.util.Map;

import com.example.mcpoauthdemo.oauth.OAuthException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OAuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthExceptionHandler.class);

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, String>> handleOAuth(OAuthException ex, HttpServletRequest request) {
        log.warn("OAuth error on {} {}: {} ({})", request.getMethod(), request.getRequestURI(),
            ex.error(), ex.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status())
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache");
        if ("invalid_client".equals(ex.error())) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"token\"");
        }
        return builder.body(Map.of("error", ex.error(), "error_description", ex.getMessage()));
    }
}
