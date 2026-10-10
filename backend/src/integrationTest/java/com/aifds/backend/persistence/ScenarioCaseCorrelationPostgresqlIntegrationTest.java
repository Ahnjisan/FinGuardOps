package com.aifds.backend.persistence;

import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseFinalDisposition;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.scenario.ScenarioCaseCorrelationService;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioCaseCorrelationPostgresqlIntegrationTest extends PostgresqlIntegrationTestSupport {
    @Autowired private ScenarioCaseCorrelationService correlation;
    @Autowired private FraudCaseRepository cases;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void picksOldestActiveRepresentativeThenExcludesClosedAndOutOfWindowCases() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var key = new ScenarioCaseCorrelationService.Correlation("SCN002", "a".repeat(64));
        FraudCase first = cases.saveAndFlush(FraudCase.open(UUID.randomUUID(), anchor));
        FraudCase second = cases.saveAndFlush(FraudCase.open(UUID.randomUUID(), anchor));
        correlation.register(key, first, tx(anchor));
        correlation.register(key, second, tx(anchor.plusSeconds(10)));
        assertThat(correlation.lockRepresentative(key, tx(anchor.plusSeconds(20))).orElseThrow()
                .getCaseId()).isEqualTo(first.getCaseId());
        Instant changed = anchor.plusSeconds(1);
        first.startReview(UUID.randomUUID().toString(), changed);
        first.resolve(FraudCaseFinalDisposition.NORMAL, changed.plusSeconds(1));
        cases.saveAndFlush(first);
        assertThat(correlation.lockRepresentative(key, tx(anchor.plusSeconds(20))).orElseThrow()
                .getCaseId()).isEqualTo(second.getCaseId());
        assertThat(correlation.lockRepresentative(key, tx(anchor.plusSeconds(1811)))).isEmpty();
    }

    @Test
    void preventsCorrelationHistoryDeletion() {
        Instant anchor = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var key = new ScenarioCaseCorrelationService.Correlation("SCN002", "b".repeat(64));
        FraudCase second = cases.saveAndFlush(FraudCase.open(UUID.randomUUID(), anchor));
        correlation.register(key, second, tx(anchor));
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM scenario_case_correlation WHERE fraud_case_id = ?", second.getId()))
                .hasMessageContaining("scenario history is immutable");
    }

    private FinancialTransaction tx(Instant occurredAt) {
        return new FinancialTransaction(UUID.randomUUID(), TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("1000000"), "KRW", occurredAt, "customer", "sender",
                "recipient", TransactionChannel.MOBILE_BANKING, "device");
    }
}
