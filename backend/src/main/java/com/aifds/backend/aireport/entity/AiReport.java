package com.aifds.backend.aireport.entity;

import java.util.UUID;

public record AiReport(long id, UUID reportId, long executionPk) { }
