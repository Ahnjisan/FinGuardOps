package com.aifds.backend.aireport.entity;

public record ProviderCallAttempt(long executionPk, int attemptNumber, String provider,
                                  String modelDigest, String quantization, Integer inputTokens,
                                  Integer outputTokens, int latencyMs, String outcome) { }
