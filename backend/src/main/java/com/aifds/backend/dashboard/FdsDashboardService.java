package com.aifds.backend.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FdsDashboardService {
    private static final List<String> LEVELS = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> SCENARIOS = List.of("SCN001", "SCN002", "SCN003", "SCN004",
            "SCN005", "SCN006", "SCN007", "SCN008");
    private static final List<String> STATUSES = List.of("OPEN", "IN_REVIEW",
            "ADDITIONAL_INFORMATION_REQUIRED", "CLOSED");
    private final FdsDashboardRepository repository;

    public FdsDashboardService(FdsDashboardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FdsDashboardResponse.Transactions transactions(FdsDashboardRange range,
            Instant asOf, String traceId) {
        Map<LocalDate, MutableTransactionDay> days = new LinkedHashMap<>();
        for (LocalDate date = range.from(); date.isBefore(range.to()); date = date.plusDays(1)) {
            days.put(date, new MutableTransactionDay());
        }
        for (var row : repository.transactions(range)) {
            MutableTransactionDay day = days.get(row.date());
            if (day == null) throw new IllegalStateException("Aggregate day outside request range");
            day.total += row.count();
            if ("UNCLASSIFIED".equals(row.riskLevel())) {
                day.unclassified += row.count();
            } else {
                if (!day.levels.containsKey(row.riskLevel())) {
                    throw new IllegalStateException("Unknown stored risk level");
                }
                day.classified += row.count();
                day.levels.merge(row.riskLevel(), row.count(), Long::sum);
            }
        }
        for (var row : repository.scenarios(range)) {
            MutableTransactionDay day = days.get(row.date());
            if (day == null || !day.scenarios.containsKey(row.scenario())) {
                throw new IllegalStateException("Unknown aggregate scenario");
            }
            day.scenarios.merge(row.scenario(), row.count(), Long::sum);
        }
        List<FdsDashboardResponse.TransactionDay> result = new ArrayList<>();
        days.forEach((date, day) -> result.add(new FdsDashboardResponse.TransactionDay(
                date, day.total, day.unclassified, day.classified,
                Map.copyOf(day.levels), Map.copyOf(day.scenarios))));
        return new FdsDashboardResponse.Transactions(range.from(), range.to(),
                FdsDashboardRange.ZONE.getId(), asOf, result, traceId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FdsDashboardResponse.Cases cases(FdsDashboardRange range,
            Instant asOf, String traceId) {
        Map<LocalDate, Long> days = new LinkedHashMap<>();
        for (LocalDate date = range.from(); date.isBefore(range.to()); date = date.plusDays(1)) {
            days.put(date, 0L);
        }
        repository.newCases(range).forEach(row -> days.put(row.date(), row.count()));
        Map<String, Long> statuses = zeroMap(STATUSES);
        repository.currentStatuses(range).forEach(row -> {
            if (!statuses.containsKey(row.status())) throw new IllegalStateException("Unknown case status");
            statuses.put(row.status(), row.count());
        });
        List<FdsDashboardResponse.CaseDay> result = new ArrayList<>();
        days.forEach((date, count) -> result.add(new FdsDashboardResponse.CaseDay(date, count)));
        return new FdsDashboardResponse.Cases(range.from(), range.to(),
                FdsDashboardRange.ZONE.getId(), asOf, result, Map.copyOf(statuses), traceId);
    }

    private static Map<String, Long> zeroMap(List<String> keys) {
        Map<String, Long> values = new LinkedHashMap<>();
        keys.forEach(key -> values.put(key, 0L));
        return values;
    }

    private static final class MutableTransactionDay {
        private long total;
        private long unclassified;
        private long classified;
        private final Map<String, Long> levels = zeroMap(LEVELS);
        private final Map<String, Long> scenarios = zeroMap(SCENARIOS);
    }
}
