package com.aifds.backend.scenario.auth;

import java.util.UUID;

/** Sent only by the locally authenticated Mock service. */
public record MockAuthRequest(UUID decisionId, String result) { }
