package com.example.mcpoauthdemo.oauth;

import org.springframework.http.HttpStatus;

public class OAuthException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public OAuthException(String error, String description) {
        this(error, description, HttpStatus.BAD_REQUEST);
    }

    public OAuthException(String error, String description, HttpStatus status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public HttpStatus status() {
        return status;
    }
}
