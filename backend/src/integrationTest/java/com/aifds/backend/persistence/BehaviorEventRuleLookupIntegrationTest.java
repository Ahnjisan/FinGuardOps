package com.aifds.backend.persistence;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionChannel;
import com.aifds.backend.transaction.entity.TransactionType;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BehaviorEventRuleLookupIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String CUSTOMER_REF = "cust_ref_rule_lookup";
    private static final String OTHER_CUSTOMER_REF =
            "cust_ref_rule_lookup_other";
    private static final Instant FROM_INCLUSIVE =
            Instant.parse("2026-07-29T10:00:00Z");
    private static final Instant TO_INCLUSIVE =
            Instant.parse("2026-07-29T11:00:00Z");
    private static final String FINGERPRINT = "b".repeat(64);

    @Autowired
    private BehaviorEventRepository behaviorEventRepository;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findsRequestedCustomerTypesWithinInclusiveRangeInBusinessOrder() {
        UUID atStart = uuid("00000000-0000-4000-8000-000000000010");
        UUID tiedFirst = uuid("00000000-0000-4000-8000-000000000020");
        UUID tiedSecond = uuid("00000000-0000-4000-8000-000000000030");
        UUID atEnd = uuid("00000000-0000-4000-8000-000000000040");

        save(atStart, BehaviorEventType.DEVICE_REGISTERED, FROM_INCLUSIVE,
                CUSTOMER_REF, null);
        save(tiedSecond, BehaviorEventType.PASSWORD_CHANGED,
                Instant.parse("2026-07-29T10:30:00Z"), CUSTOMER_REF, null);
        save(tiedFirst, BehaviorEventType.DEVICE_REGISTERED,
                Instant.parse("2026-07-29T10:30:00Z"), CUSTOMER_REF, null);
        save(atEnd, BehaviorEventType.PASSWORD_CHANGED, TO_INCLUSIVE,
                CUSTOMER_REF, null);
        save(uuid("00000000-0000-4000-8000-000000000050"),
                BehaviorEventType.DEVICE_REGISTERED,
                FROM_INCLUSIVE.minusSeconds(1), CUSTOMER_REF, null);
        save(uuid("00000000-0000-4000-8000-000000000060"),
                BehaviorEventType.DEVICE_REGISTERED,
                TO_INCLUSIVE.plusSeconds(1), CUSTOMER_REF, null);
        save(uuid("00000000-0000-4000-8000-000000000070"),
                BehaviorEventType.DEVICE_REGISTERED,
                Instant.parse("2026-07-29T10:45:00Z"), OTHER_CUSTOMER_REF, null);
        save(uuid("00000000-0000-4000-8000-000000000080"),
                BehaviorEventType.OTP_REISSUED,
                Instant.parse("2026-07-29T10:45:00Z"), CUSTOMER_REF, null);
        entityManager.clear();

        List<BehaviorEvent> results = behaviorEventRepository
                .findForRuleEvaluation(
                        CUSTOMER_REF,
                        Set.of(
                                BehaviorEventType.DEVICE_REGISTERED,
                                BehaviorEventType.PASSWORD_CHANGED
                        ),
                        FROM_INCLUSIVE,
                        TO_INCLUSIVE,
                        PageRequest.of(0, 10, Sort.unsorted())
                );

        assertThat(results).extracting(BehaviorEvent::getEventId)
                .containsExactly(atEnd, tiedFirst, tiedSecond, atStart);
    }

    @Test
    void limitsRowsWithoutCountQueryContractAndKeepsTransactionLazy() {
        FinancialTransaction transaction = saveTransaction();
        UUID newest = uuid("00000000-0000-4000-8000-000000000110");
        save(
                uuid("00000000-0000-4000-8000-000000000100"),
                BehaviorEventType.DEVICE_REGISTERED,
                Instant.parse("2026-07-29T10:00:00Z"),
                CUSTOMER_REF,
                transaction
        );
        save(
                newest,
                BehaviorEventType.DEVICE_REGISTERED,
                Instant.parse("2026-07-29T10:30:00Z"),
                CUSTOMER_REF,
                transaction
        );
        save(
                uuid("00000000-0000-4000-8000-000000000120"),
                BehaviorEventType.DEVICE_REGISTERED,
                Instant.parse("2026-07-29T10:15:00Z"),
                CUSTOMER_REF,
                transaction
        );
        entityManager.clear();

        List<BehaviorEvent> results = behaviorEventRepository
                .findForRuleEvaluation(
                        CUSTOMER_REF,
                        Set.of(BehaviorEventType.DEVICE_REGISTERED),
                        FROM_INCLUSIVE,
                        TO_INCLUSIVE,
                        PageRequest.of(0, 1, Sort.unsorted())
                );
        PersistenceUnitUtil persistenceUnitUtil =
                entityManager.getEntityManagerFactory()
                        .getPersistenceUnitUtil();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEventId()).isEqualTo(newest);
        assertThat(persistenceUnitUtil.isLoaded(
                results.get(0),
                "financialTransaction"
        )).isFalse();
    }

    @Test
    void internalCallerUsesNonEmptyTypesAndPositiveFiniteLimit() {
        assertThat(Set.of(BehaviorEventType.DEVICE_REGISTERED)).isNotEmpty();
        assertThat(PageRequest.of(0, 1, Sort.unsorted()).getPageSize())
                .isEqualTo(1);
        assertThatThrownBy(() -> PageRequest.of(0, 0, Sort.unsorted()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freezesTheLatestOneThousandEventsInContractOrder() {
        List<BehaviorEvent> events = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index <= 1_000; index++) {
            UUID eventId = UUID.randomUUID();
            ids.add(eventId);
            events.add(new BehaviorEvent(
                    eventId,
                    BehaviorEventType.DEVICE_REGISTERED,
                    TO_INCLUSIVE.minusSeconds(index),
                    CUSTOMER_REF,
                    null,
                    "device_ref_rule_lookup",
                    null,
                    null,
                    FINGERPRINT
            ));
        }
        behaviorEventRepository.saveAllAndFlush(events);
        entityManager.clear();

        List<BehaviorEvent> results = behaviorEventRepository
                .findForRuleEvaluation(
                        CUSTOMER_REF,
                        Set.of(BehaviorEventType.DEVICE_REGISTERED),
                        FROM_INCLUSIVE,
                        TO_INCLUSIVE,
                        PageRequest.of(0, 1_000, Sort.unsorted())
                );

        assertThat(results).hasSize(1_000);
        assertThat(results.get(0).getEventId()).isEqualTo(ids.get(0));
        assertThat(results.get(999).getEventId()).isEqualTo(ids.get(999));
        assertThat(results).extracting(BehaviorEvent::getEventId)
                .doesNotContain(ids.get(1_000));
    }

    private void save(
            UUID eventId,
            BehaviorEventType eventType,
            Instant occurredAt,
            String customerRef,
            FinancialTransaction transaction
    ) {
        behaviorEventRepository.saveAndFlush(new BehaviorEvent(
                eventId,
                eventType,
                occurredAt,
                customerRef,
                null,
                "device_ref_rule_lookup",
                null,
                transaction,
                FINGERPRINT
        ));
    }

    private FinancialTransaction saveTransaction() {
        return transactionRepository.saveAndFlush(new FinancialTransaction(
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                TransactionType.ACCOUNT_TRANSFER,
                new BigDecimal("10000000"),
                "KRW",
                Instant.parse("2026-07-29T10:40:00Z"),
                CUSTOMER_REF,
                "acct_ref_rule_lookup_sender",
                "acct_ref_rule_lookup_recipient",
                TransactionChannel.MOBILE_BANKING,
                "device_ref_rule_lookup"
        ));
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
