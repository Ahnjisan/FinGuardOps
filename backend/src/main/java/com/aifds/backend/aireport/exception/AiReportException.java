package com.aifds.backend.aireport.exception;

import org.springframework.http.HttpStatus;

public class AiReportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AiReportException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
