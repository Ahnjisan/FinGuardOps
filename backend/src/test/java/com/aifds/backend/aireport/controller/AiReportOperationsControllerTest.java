package com.aifds.backend.aireport.controller;

import com.aifds.backend.aireport.dto.AiReportUsageQuery;
import com.aifds.backend.aireport.exception.AiReportException;
import com.aifds.backend.aireport.service.AiReportOperationsQueryService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AiReportOperationsControllerTest {
    private final AiReportOperationsController controller =
            new AiReportOperationsController(mock(AiReportOperationsQueryService.class));

    @Test
    void rejectsNonCanonicalRequestIdBeforeQuery() {
        var error = assertThrows(AiReportException.class,
                () -> controller.detail("NOT-A-UUID", "trace-query-001"));
        assertEquals(400, error.status().value());
    }

    @Test
    void distinguishesMalformedAndSemanticTimeErrors() {
        var malformed = assertThrows(AiReportException.class,
                () -> AiReportUsageQuery.parse(Map.of("from", new String[]{"bad"},
                        "to", new String[]{"2026-10-02T00:00:00Z"}), true));
        assertEquals(400, malformed.status().value());
        var range = assertThrows(AiReportException.class,
                () -> AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-02T00:00:00Z"},
                        "to", new String[]{"2026-10-01T00:00:00Z"}), false));
        assertEquals(422, range.status().value());
        assertEquals("INVALID_TIME_RANGE", range.fieldCode());
    }

    @Test
    void rejectsDuplicateAndSummaryPageParameters() {
        assertThrows(AiReportException.class,
                () -> AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z",
                        "2026-10-01T00:00:00Z"}, "to", new String[]{"2026-10-02T00:00:00Z"}), false));
        assertThrows(AiReportException.class,
                () -> AiReportUsageQuery.parse(Map.of("from", new String[]{"2026-10-01T00:00:00Z"},
                        "to", new String[]{"2026-10-02T00:00:00Z"}, "page", new String[]{"0"}), true));
    }
}
