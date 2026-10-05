package com.aifds.backend.aireport.entity;

import java.util.UUID;

public record AiReportExecution(long id, UUID executionId, long casePk, long detectionPk,
                                int detectionResultVersion, String promptVersion,
                                String modelVersion, AiReportStatus status) { }
