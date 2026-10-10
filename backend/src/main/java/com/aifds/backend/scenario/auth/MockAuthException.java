package com.aifds.backend.scenario.auth;

import org.springframework.http.HttpStatus;

public final class MockAuthException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MockAuthException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
