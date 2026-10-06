package com.aifds.backend.aireport.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiReportHttpClientTest {
    @Test
    void separatesConnectionFailureFromReadTimeoutWithoutExposingExceptionText() {
        assertEquals("FASTAPI_CONNECTION_FAILED", AiReportHttpClient.classifyAccessFailure(
                new ResourceAccessException("private address", new ConnectException("private address"))));
        assertEquals("FASTAPI_CONNECTION_FAILED", AiReportHttpClient.classifyAccessFailure(
                new ResourceAccessException("private address", new SocketTimeoutException("connect timed out"))));
        assertEquals("FASTAPI_TIMEOUT", AiReportHttpClient.classifyAccessFailure(
                new ResourceAccessException("private address", new SocketTimeoutException("Read timed out"))));
    }
}
