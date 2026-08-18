package com.aifds.backend.externalrisk.domain;

public enum ExternalRiskFailureCategory {
    TIMEOUT,
    UNAVAILABLE,
    INVALID_REQUEST,
    UNSUPPORTED_CAPABILITY,
    INVALID_RESPONSE,
    TRANSFORMATION_ERROR
}
