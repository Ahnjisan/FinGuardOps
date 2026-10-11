package com.aifds.backend.scenario.mock;

import org.springframework.http.HttpStatus;

public final class MockFactException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MockFactException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
