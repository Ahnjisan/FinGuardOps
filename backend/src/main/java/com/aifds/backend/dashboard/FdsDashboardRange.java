package com.aifds.backend.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public record FdsDashboardRange(LocalDate from, LocalDate to, Instant start, Instant end) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static FdsDashboardRange parse(Map<String, String[]> parameters, Instant now) {
        if (!parameters.keySet().stream().allMatch(key -> key.equals("from") || key.equals("to"))
                || parameters.values().stream().anyMatch(values -> values.length != 1)) {
            throw new FdsDashboardValidationException();
        }
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate from;
        LocalDate to;
        if (parameters.isEmpty()) {
            to = today.plusDays(1);
            from = to.minusDays(7);
        } else {
            if (!parameters.containsKey("from") || !parameters.containsKey("to")) {
                throw new FdsDashboardValidationException();
            }
            try {
                from = LocalDate.parse(parameters.get("from")[0]);
                to = LocalDate.parse(parameters.get("to")[0]);
            } catch (DateTimeParseException | NullPointerException exception) {
                throw new FdsDashboardValidationException();
            }
            if (!from.toString().equals(parameters.get("from")[0])
                    || !to.toString().equals(parameters.get("to")[0])) {
                throw new FdsDashboardValidationException();
            }
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1 || days > 31) {
            throw new FdsDashboardValidationException();
        }
        return new FdsDashboardRange(from, to,
                from.atStartOfDay(ZONE).toInstant(), to.atStartOfDay(ZONE).toInstant());
    }
}
