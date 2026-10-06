package com.aifds.backend.aireport.exception;

import org.springframework.http.HttpStatus;

public class AiReportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String field;
    private final String fieldCode;

    public AiReportException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
        this.field = null;
        this.fieldCode = null;
    }

    public AiReportException(HttpStatus status, String code, String field, String fieldCode) {
        super(code);
        this.status = status;
        this.code = code;
        this.field = field;
        this.fieldCode = fieldCode;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String field() { return field; }
    public String fieldCode() { return fieldCode; }
}
