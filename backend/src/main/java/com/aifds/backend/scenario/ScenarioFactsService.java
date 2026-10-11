package com.aifds.backend.scenario;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.externalrisk.domain.ExternalRiskSnapshot;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectDigest;
import com.aifds.backend.externalrisk.domain.ExternalRiskSubjectType;
import com.aifds.backend.externalrisk.domain.ExternalRiskType;
import com.aifds.backend.externalrisk.domain.ExternalRiskReasonCode;
import com.aifds.backend.scenario.history.ScenarioHistoryRepository;
import com.aifds.backend.scenario.history.ScenarioHistoryRepository.DeviceUse;
import com.aifds.backend.scenario.mock.MockFact;
import com.aifds.backend.scenario.mock.MockFactRepository;
import com.aifds.backend.scenario.mock.MockFactType;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.entity.TransactionType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ScenarioFactsService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_EVENTS = 1000;
    private static final int MAX_FACTS = 1000;
    private static final Instant COMPLETE_LIFETIME_START = Instant.EPOCH;
    private final ScenarioHistoryRepository history;
    private final MockFactRepository mockFacts;
    private final BehaviorEventRepository behaviors;

    public ScenarioFactsService(ScenarioHistoryRepository history,
                                MockFactRepository mockFacts,
                                BehaviorEventRepository behaviors) {
        this.history = history;
        this.mockFacts = mockFacts;
        this.behaviors = behaviors;
    }

    @Transactional(readOnly = true)
    public ScenarioFacts snapshot(FinancialTransaction transaction,
                                  ExternalRiskSnapshot externalRisk) {
        FinancialTransaction tx = Objects.requireNonNull(transaction);
        Instant t = tx.getOccurredAt();
        if (tx.getTransactionType() != TransactionType.ACCOUNT_TRANSFER
                && tx.getTransactionType() != TransactionType.OPEN_BANKING_TRANSFER) {
            return new ScenarioFacts("SCN001-002-contract-v1", "UNKNOWN", 0, 0,
                    BigDecimal.ZERO, null, "UNKNOWN", null, null, "UNKNOWN", null,
                    null, "UNKNOWN", null, null, null, "UNKNOWN", null, null,
                    null, null, "UNKNOWN", "UNKNOWN", null, "UNKNOWN", null,
                    false, null, null, null, null, null, null, null);
        }
        List<MockFact> facts = mockFacts.findAsOf(tx.getExternalCustomerRef(),
                List.of(MockFactType.TRANSFER_HISTORY_COMPLETE,
                        MockFactType.DEVICE_HISTORY_COMPLETE, MockFactType.DEVICE_TRUST,
                        MockFactType.LIMIT_CHANGE, MockFactType.IDENTITY_CHECK,
                        MockFactType.APP_SECURITY, MockFactType.NETWORK_REGION),
                t, t.minus(Duration.ofHours(24)),
                PageRequest.of(0, MAX_FACTS + 1));
        if (facts.size() > MAX_FACTS) {
            throw new IllegalStateException("SCENARIO_MOCK_FACT_LIMIT_EXCEEDED");
        }
        List<BehaviorEvent> events = behaviors.findForMlEvaluation(
                tx.getExternalCustomerRef(), EnumSet.of(
                        BehaviorEventType.PASSWORD_CHANGED,
                        BehaviorEventType.TRANSFER_LIMIT_CHANGED,
                        BehaviorEventType.BENEFICIARY_REGISTERED),
                t.minus(Duration.ofHours(24)), t, PageRequest.of(0, MAX_EVENTS + 1));
        if (events.size() > MAX_EVENTS) {
            throw new IllegalStateException("SCENARIO_EVENT_LIMIT_EXCEEDED");
        }
        MockFact transferCoverage = coverage(facts, MockFactType.TRANSFER_HISTORY_COMPLETE, t);
        MockFact deviceCoverage = coverage(facts, MockFactType.DEVICE_HISTORY_COMPLETE, t);
        var baseline = history.customerBaseline(tx.getExternalCustomerRef(),
                t.minus(Duration.ofDays(90)), t);
        String baselineStatus = "UNKNOWN";
        BigDecimal baselineMean = null;
        if (transferCoverage != null
                && !transferCoverage.getCoverageFrom().isAfter(t.minus(Duration.ofDays(90)))
                && baseline.unknownCount() == 0) {
            baselineStatus = baseline.eligibleCount() >= 10 && baseline.eligibleDays() >= 3
                    ? "AVAILABLE" : "INSUFFICIENT";
            if ("AVAILABLE".equals(baselineStatus)) {
                baselineMean = baseline.eligibleSum().divide(
                        BigDecimal.valueOf(baseline.eligibleCount()), 6, RoundingMode.HALF_UP);
            }
        }
        String recipientStatus = "UNKNOWN";
        if (tx.getRecipientAccountRef() != null) {
            var prior = history.priorRecipient(tx.getExternalCustomerRef(),
                    tx.getRecipientAccountRef(), t);
            if (prior.eligibleCount() > 0) {
                recipientStatus = "KNOWN";
            } else if (prior.unknownCount() == 0 && transferCoverage != null
                    && !transferCoverage.getCoverageFrom().isAfter(COMPLETE_LIFETIME_START)) {
                recipientStatus = "FIRST";
            }
        }
        BehaviorEvent beneficiary = events.stream().filter(e ->
                e.getEventType() == BehaviorEventType.BENEFICIARY_REGISTERED
                && Objects.equals(e.getBeneficiaryRef(), tx.getRecipientAccountRef())
                && !e.getOccurredAt().isBefore(t.minus(Duration.ofHours(24))))
                .findFirst().orElse(null);

        String deviceStatus = "UNKNOWN";
        DeviceUse firstDevice = null;
        MockFact trust = null;
        if (tx.getDeviceRef() != null && deviceCoverage != null
                && !deviceCoverage.getCoverageFrom().isAfter(COMPLETE_LIFETIME_START)) {
            firstDevice = history.firstDeviceUse(tx.getExternalCustomerRef(),
                    tx.getDeviceRef(), deviceCoverage.getCoverageFrom(), t);
            if (firstDevice != null) {
                if (firstDevice.occurredAt().isBefore(t.minus(Duration.ofHours(24)))) {
                    deviceStatus = "KNOWN";
                } else {
                    trust = latest(facts, MockFactType.DEVICE_TRUST,
                            tx.getSenderAccountRef(), tx.getDeviceRef(), null,
                            t.minus(Duration.ofHours(24)));
                    MockFact globalTrust = latest(facts, MockFactType.DEVICE_TRUST,
                            null, tx.getDeviceRef(), null,
                            t.minus(Duration.ofHours(24)));
                    if (trust != null && globalTrust != null
                            && !trust.getAssertionState().equals(globalTrust.getAssertionState())) {
                        trust = null;
                    } else if (trust == null) trust = globalTrust;
                    if (trust != null) {
                        deviceStatus = "VERIFIED".equals(trust.getAssertionState())
                                ? "NEW_VERIFIED" : "NEW_UNVERIFIED";
                    }
                }
            }
        }

        MockFact limit = latest(facts, MockFactType.LIMIT_CHANGE,
                tx.getSenderAccountRef(), null, null, t.minus(Duration.ofMinutes(30)));
        String limitStatus = limit == null ? "UNKNOWN" :
                limit.getNewLimit().compareTo(limit.getPreviousLimit()) > 0
                        && "SUCCEEDED".equals(limit.getAssertionState())
                        ? "RAISED" : "NOT_RAISED";
        final BehaviorEvent limitEvent = limit == null ? null : events.stream()
                .filter(e -> e.getEventId().equals(limit.getBehaviorEventId()))
                .findFirst().orElse(null);
        if (limitEvent == null) limitStatus = "UNKNOWN";
        BehaviorEvent password = limitEvent == null ? null : events.stream()
                .filter(e -> e.getEventType() == BehaviorEventType.PASSWORD_CHANGED
                        && Objects.equals(e.getAccountRef(), tx.getSenderAccountRef())
                        && e.getOccurredAt().isBefore(limitEvent.getOccurredAt())
                        && !e.getOccurredAt().isBefore(
                                limitEvent.getOccurredAt().minus(Duration.ofMinutes(30))))
                .findFirst().orElse(null);
        String priorLimitExceeded = "UNKNOWN";
        if ("RAISED".equals(limitStatus)) {
            if ("PER_TRANSFER".equals(limit.getLimitScope())) {
                priorLimitExceeded = tx.getAmount().compareTo(limit.getPreviousLimit()) > 0
                        ? "TRUE" : "FALSE";
            } else if ("DAILY".equals(limit.getLimitScope())) {
                Instant dayStart = t.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
                var daily = history.dailyUsed(tx.getExternalCustomerRef(),
                        tx.getSenderAccountRef(), dayStart, t);
                if (daily.unknownCount() == 0 && transferCoverage != null
                        && !transferCoverage.getCoverageFrom().isAfter(dayStart)) {
                    priorLimitExceeded = daily.eligibleSum().add(tx.getAmount())
                            .compareTo(limit.getPreviousLimit()) > 0 ? "TRUE" : "FALSE";
                }
            }
        }
        List<MockFact> identityFacts = password == null ? List.of() : facts.stream().filter(f ->
                f.getFactType() == MockFactType.IDENTITY_CHECK
                && Objects.equals(f.getBehaviorEventId(), password.getEventId()))
                .toList();
        MockFact identity = identityFacts.isEmpty() ? null : identityFacts.get(0);
        if (identity != null && identityFacts.stream().anyMatch(f ->
                !Objects.equals(f.getAssertionState(), identityFacts.get(0).getAssertionState()))) {
            identity = null;
        }
        MockFact app = latest(facts, MockFactType.APP_SECURITY,
                null, tx.getDeviceRef(), null, t.minus(Duration.ofHours(24)));
        MockFact region = latest(facts, MockFactType.NETWORK_REGION,
                null, tx.getDeviceRef(), null, t.minus(Duration.ofMinutes(15)));
        return new ScenarioFacts("SCN001-002-contract-v1", baselineStatus,
                baseline.eligibleCount(), baseline.eligibleDays(), baseline.eligibleSum(),
                baselineMean,
                recipientStatus, beneficiary == null ? null : beneficiary.getEventId(),
                beneficiary == null ? null : beneficiary.getOccurredAt(),
                deviceStatus, firstDevice == null ? null : firstDevice.eventId(),
                firstDevice == null ? null : firstDevice.occurredAt(),
                limitStatus, limit == null ? null : limit.getLimitScope(),
                limit == null ? null : limit.getPreviousLimit(),
                limit == null ? null : limit.getNewLimit(), priorLimitExceeded,
                password == null ? null : password.getEventId(),
                password == null ? null : password.getOccurredAt(),
                limitEvent == null ? null : limitEvent.getEventId(),
                limitEvent == null ? null : limitEvent.getOccurredAt(),
                identity == null ? "UNKNOWN" : identity.getAssertionState(),
                app == null ? "UNKNOWN" : app.getAssertionState(),
                app == null ? null : app.getOccurredAt(),
                region == null ? "UNKNOWN" : region.getAssertionState(),
                region == null ? null : region.getOccurredAt(),
                exactDeviceMatch(tx, externalRisk),
                transferCoverage == null ? null : transferCoverage.getFactId(),
                deviceCoverage == null ? null : deviceCoverage.getFactId(),
                trust == null ? null : trust.getFactId(),
                limit == null ? null : limit.getFactId(),
                identity == null ? null : identity.getFactId(),
                app == null ? null : app.getFactId(),
                region == null ? null : region.getFactId());
    }

    private boolean exactDeviceMatch(FinancialTransaction tx, ExternalRiskSnapshot snapshot) {
        if ((tx.getTransactionType() != com.aifds.backend.transaction.entity.TransactionType.ACCOUNT_TRANSFER
                && tx.getTransactionType() != com.aifds.backend.transaction.entity.TransactionType.OPEN_BANKING_TRANSFER)
                || snapshot == null || tx.getDeviceRef() == null
                || !tx.getTransactionId().equals(snapshot.transactionId())
                || !tx.getOccurredAt().equals(snapshot.evaluationCutoffAt())
                || snapshot.providerAsOf().isBefore(tx.getOccurredAt().minus(Duration.ofHours(24)))
                || snapshot.providerAsOf().isAfter(tx.getOccurredAt())
                || snapshot.lookedUpAt().isBefore(tx.getOccurredAt())) {
            return false;
        }
        String expected = ExternalRiskSubjectDigest.of(tx.getDeviceRef());
        return snapshot.matches().stream().anyMatch(match ->
                match.subjectType() == ExternalRiskSubjectType.DEVICE
                && match.riskType() == ExternalRiskType.RISK_DEVICE
                && match.reasonCode() == ExternalRiskReasonCode.RISK_DEVICE
                && expected.equals(match.subjectDigest()));
    }

    private MockFact coverage(List<MockFact> facts, MockFactType type, Instant t) {
        List<MockFact> eligible = facts.stream().filter(f -> f.getFactType() == type
                && !f.getCoverageThrough().isBefore(t)).toList();
        if (eligible.isEmpty()) return null;
        Instant from = eligible.get(0).getCoverageFrom();
        return eligible.stream().allMatch(f -> from.equals(f.getCoverageFrom()))
                ? eligible.get(0) : null;
    }

    private MockFact latest(List<MockFact> facts, MockFactType type,
                            String account, String device, UUID eventId, Instant lower) {
        if (device == null && (type == MockFactType.DEVICE_TRUST
                || type == MockFactType.APP_SECURITY || type == MockFactType.NETWORK_REGION)) {
            return null;
        }
        List<MockFact> eligible = facts.stream().filter(f -> f.getFactType() == type
                && Objects.equals(f.getAccountRef(), account)
                && Objects.equals(f.getDeviceRef(), device)
                && (eventId == null || eventId.equals(f.getBehaviorEventId()))
                && !f.getOccurredAt().isBefore(lower))
                .toList();
        if (eligible.isEmpty()) return null;
        MockFact first = eligible.get(0);
        return eligible.stream().filter(f -> f.getOccurredAt().equals(first.getOccurredAt()))
                .allMatch(f -> Objects.equals(f.getAssertionState(), first.getAssertionState())
                        && Objects.equals(f.getPreviousLimit(), first.getPreviousLimit())
                        && Objects.equals(f.getNewLimit(), first.getNewLimit())
                        && Objects.equals(f.getLimitScope(), first.getLimitScope())
                        && Objects.equals(f.getBehaviorEventId(), first.getBehaviorEventId())
                        && Objects.equals(f.getCountryCode(), first.getCountryCode()))
                ? first : null;
    }
}
