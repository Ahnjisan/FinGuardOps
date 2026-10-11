package com.aifds.backend.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class FdsDashboardResponse {
    private FdsDashboardResponse() { }

    public record TransactionDay(LocalDate date, long total, long unclassified,
            long classified, Map<String, Long> riskLevels, Map<String, Long> scenarios) { }

    public record Transactions(LocalDate from, LocalDate to, String timeZone,
            Instant asOf, List<TransactionDay> days, String traceId) { }

    public record CaseDay(LocalDate date, long newCases) { }

    public record Cases(LocalDate from, LocalDate to, String timeZone, Instant asOf,
            List<CaseDay> days, Map<String, Long> currentStatuses, String traceId) { }
}
