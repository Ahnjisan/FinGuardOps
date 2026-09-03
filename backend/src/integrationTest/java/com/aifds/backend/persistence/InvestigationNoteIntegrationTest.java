package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListResponse;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.exception.InvestigationNoteException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.service.InvestigationNoteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.security.test.context.support.WithMockUser(
        authorities = {
                "case:workflow:write",
                "case:resolution:write",
                "case-note:write"
        }
)
class InvestigationNoteIntegrationTest extends PostgresqlIntegrationTestSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-09-02T00:00:00Z");
    private static final String ASSIGNEE = "20000000-0000-4000-8000-000000000002";

    @Autowired InvestigationNoteService service;
    @Autowired FraudCaseWorkflowService workflowService;
    @Autowired FraudCaseRepository fraudCaseRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired ObjectMapper objectMapper;

    private final ThreadLocal<ServiceMethod> activeServiceMethod =
            new ThreadLocal<>();

    @Test
    void freshV13CreatesExactAppendOnlySchemaAndDefendsUnicodeAndAuditJsonNull() {
        assertThat(flyway.info().applied()).hasSize(13);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(columns("investigation_note")).containsExactlyInAnyOrder(
                "id", "note_id", "fraud_case_id", "author_type", "author_ref",
                "content", "created_at"
        );
        assertThat(constraints("investigation_note")).contains(
                "pk_investigation_note", "uq_investigation_note_note_id",
                "ck_investigation_note_note_id_uuid_v4", "ck_investigation_note_author",
                "ck_investigation_note_content_length",
                "ck_investigation_note_content_non_whitespace",
                "ck_investigation_note_content_control",
                "fk_investigation_note_fraud_case"
        );
        assertThat(indexes("investigation_note"))
                .contains("ix_investigation_note_case_created");

        CaseFixture fixture = insertReviewCase(6);
        UUID noteId = insertNote(fixture.id(), "😀", CREATED_AT.plus(1, ChronoUnit.MICROS));
        assertThat(insertNote(
                fixture.id(), "😀".repeat(4_000),
                CREATED_AT.plus(2, ChronoUnit.MICROS)
        )).isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE investigation_note SET content = ? WHERE note_id = ?", "changed", noteId
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM investigation_note WHERE note_id = ?", noteId
        )).isInstanceOf(DataAccessException.class);
        for (String invalid : new String[]{
                "", "   ", "\u00a0\u2003", "a\u0000b", "a\u0009b", "a\u0085b",
                "😀".repeat(4_001)
        }) {
            assertThatThrownBy(() -> insertNote(
                    fixture.id(), invalid, CREATED_AT.plus(3, ChronoUnit.MICROS)
            )).isInstanceOf(DataAccessException.class);
        }

        for (String metadata : new String[]{
                "{}", "{\"noteId\":null}", "{\"noteId\":1}",
                "{\"noteId\":\"not-a-uuid\"}",
                "{\"noteId\":\"10000000-0000-4000-8000-000000000001\",\"extra\":true}"
        }) {
            assertThatThrownBy(() -> insertNoteAudit(fixture.caseId(), metadata))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void createsOncePreservesContentAdvancesVersionAndReturnsDeterministicPages() {
        CaseFixture fixture = insertReviewCase(6);
        String content = "  <script>alert(1)</script>\r\nSELECT * FROM account; 😀  ";

        InvestigationNoteCreateResponse response = service.create(
                new FraudCaseNoteCommand.Create(fixture.caseId(), content, 6),
                "trace_note_create_01"
        );

        assertThat(response.content()).isEqualTo(content);
        assertThat(response.authorType().name()).isEqualTo("SYSTEM");
        assertThat(response.authorRef()).isEqualTo("finguardops-backend");
        assertThat(response.concurrencyVersion()).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT concurrency_version FROM fraud_case WHERE id = ?",
                Long.class, fixture.id()
        )).isEqualTo(7L);
        assertThat(jdbc.queryForObject(
                "SELECT created_at = (SELECT last_changed_at FROM fraud_case WHERE id = ?) "
                        + "FROM investigation_note WHERE note_id = ?",
                Boolean.class, fixture.id(), response.noteId()
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT metadata::text FROM audit_log WHERE action = 'CASE_NOTE_CREATED' AND case_id = ?",
                String.class, fixture.caseId()
        )).isEqualTo("{\"noteId\": \"" + response.noteId() + "\"}");
        assertThat(jdbc.queryForObject(
                "SELECT before_value_summary IS NULL AND after_value_summary IS NULL "
                        + "FROM audit_log WHERE action = 'CASE_NOTE_CREATED' AND case_id = ?",
                Boolean.class, fixture.caseId()
        )).isTrue();

        assertThatThrownBy(() -> service.create(
                new FraudCaseNoteCommand.Create(fixture.caseId(), content, 6),
                "trace_note_create_02"
        )).isInstanceOf(InvestigationNoteException.class)
                .extracting("reason")
                .isEqualTo(InvestigationNoteException.Reason.CONCURRENT_MODIFICATION);
        assertThat(noteCount(fixture.caseId())).isEqualTo(1);

        Instant tie = response.createdAt().plus(1, ChronoUnit.MICROS);
        UUID first = insertNote(fixture.id(), "first", tie);
        UUID second = insertNote(fixture.id(), "second", tie);
        var asc = service.list(new FraudCaseNoteCommand.ListQuery(
                fixture.caseId(), 0, 2, FraudCaseNoteCommand.Direction.ASC
        ), "trace_note_list_01");
        var desc = service.list(new FraudCaseNoteCommand.ListQuery(
                fixture.caseId(), 0, 2, FraudCaseNoteCommand.Direction.DESC
        ), "trace_note_list_02");
        assertThat(asc.items()).extracting("noteId")
                .containsExactly(response.noteId(), first);
        assertThat(desc.items()).extracting("noteId")
                .containsExactly(second, first);
        assertThat(asc.page().totalElements()).isEqualTo(3);

        CaseFixture empty = insertReviewCase(0);
        assertThat(service.list(new FraudCaseNoteCommand.ListQuery(
                empty.caseId(), 0, 20, FraudCaseNoteCommand.Direction.ASC
        ), "trace_note_empty_01").items()).isEmpty();
    }

    @Test
    void paginatesAcrossBoundariesWithoutDuplicatesOmissionsOrCaseLeakage()
            throws JsonProcessingException {
        CaseFixture fixture = insertReviewCase(0);
        CaseFixture otherCase = insertReviewCase(0);
        Instant firstTime = CREATED_AT.plus(1, ChronoUnit.MICROS);
        Instant tiedTime = CREATED_AT.plus(2, ChronoUnit.MICROS);
        Instant lastTime = CREATED_AT.plus(3, ChronoUnit.MICROS);
        List<UUID> ascendingExpected = List.of(
                insertNote(fixture.id(), "first", firstTime),
                insertNote(fixture.id(), "tie-1", tiedTime),
                insertNote(fixture.id(), "tie-2", tiedTime),
                insertNote(fixture.id(), "tie-3", tiedTime),
                insertNote(fixture.id(), "last-1", lastTime),
                insertNote(fixture.id(), "last-2", lastTime),
                insertNote(fixture.id(), "last-3", lastTime)
        );
        UUID foreignNote = insertNote(
                otherCase.id(), "must not leak", tiedTime
        );

        List<InvestigationNoteListResponse> ascendingPages = listPages(
                fixture.caseId(), FraudCaseNoteCommand.Direction.ASC
        );
        List<InvestigationNoteListResponse> descendingPages = listPages(
                fixture.caseId(), FraudCaseNoteCommand.Direction.DESC
        );
        List<UUID> ascendingActual = noteIds(ascendingPages);
        List<UUID> descendingActual = noteIds(descendingPages);
        List<UUID> descendingExpected = new ArrayList<>(ascendingExpected);
        Collections.reverse(descendingExpected);

        assertThat(ascendingActual).containsExactlyElementsOf(ascendingExpected);
        assertThat(descendingActual).containsExactlyElementsOf(descendingExpected);
        assertThat(ascendingActual)
                .doesNotContain(foreignNote)
                .doesNotHaveDuplicates();
        assertThat(descendingActual)
                .doesNotContain(foreignNote)
                .doesNotHaveDuplicates();
        assertThat(ascendingActual).containsExactlyInAnyOrderElementsOf(
                ascendingExpected
        );
        assertThat(descendingActual).containsExactlyInAnyOrderElementsOf(
                ascendingExpected
        );
        assertPageMetadata(ascendingPages);
        assertPageMetadata(descendingPages);

        InvestigationNoteListResponse beyond = service.list(
                new FraudCaseNoteCommand.ListQuery(
                        fixture.caseId(), 3, 3,
                        FraudCaseNoteCommand.Direction.ASC
                ),
                "trace_note_page_beyond_01"
        );
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.page().number()).isEqualTo(3);
        assertThat(beyond.page().size()).isEqualTo(3);
        assertThat(beyond.page().totalElements()).isEqualTo(7);
        assertThat(beyond.page().totalPages()).isEqualTo(3);
        assertThat(objectMapper.writeValueAsString(ascendingPages))
                .doesNotContain("\"id\":");
    }

    @Test
    void auditFailureRollsBackParentVersionTimestampAndNote() {
        CaseFixture fixture = insertReviewCase(6);
        Instant before = lastChangedAt(fixture.id());
        jdbc.execute("""
                ALTER TABLE audit_log ADD CONSTRAINT ck_test_reject_case_note
                CHECK (action <> 'CASE_NOTE_CREATED')
                """);
        try {
            assertThatThrownBy(() -> service.create(
                    new FraudCaseNoteCommand.Create(fixture.caseId(), "rollback memo", 6),
                    "trace_note_rollback_01"
            )).isInstanceOf(InvestigationNoteException.class)
                    .extracting("reason")
                    .isEqualTo(InvestigationNoteException.Reason.INTERNAL_FAILURE);
            assertThat(noteCount(fixture.caseId())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT concurrency_version FROM fraud_case WHERE id = ?",
                    Long.class, fixture.id()
            )).isEqualTo(6L);
            assertThat(lastChangedAt(fixture.id())).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT ck_test_reject_case_note");
        }
    }

    @Test
    void noteRacesWithNoteResolutionStatusAndAssigneeFiveTimesEach() throws Exception {
        for (RaceKind kind : RaceKind.values()) {
            for (int repetition = 0; repetition < 5; repetition++) {
                assertRace(kind);
            }
        }
    }

    private void assertRace(RaceKind kind) throws Exception {
        CaseFixture fixture = insertReviewCase(6);
        ReadBarrier barrier = installReadBarrier(fixture.caseId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CommandOutcome noteResult;
        CommandOutcome otherResult;
        try {
            Future<CommandOutcome> note = executor.submit(contender(
                    fixture, ServiceMethod.NOTE_CREATE
            ));
            Future<CommandOutcome> other = executor.submit(contender(
                    fixture, kind.serviceMethod()
            ));
            assertThat(barrier.loaded().await(20, TimeUnit.SECONDS)).isTrue();
            assertReadEvidence(barrier, kind);
            barrier.release().countDown();
            noteResult = note.get(20, TimeUnit.SECONDS);
            otherResult = other.get(20, TimeUnit.SECONDS);
            assertOneSuccessAndOneProductionConflict(noteResult, otherResult);
            assertThat(barrier.evidence())
                    .extracting(ReadEvidence::threadId)
                    .containsExactlyInAnyOrder(
                            noteResult.threadId(), otherResult.threadId()
                    );
        } finally {
            barrier.release().countDown();
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            restoreServiceRepositories();
            assertThat(terminated).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT concurrency_version FROM fraud_case WHERE id = ?",
                Long.class, fixture.id()
        )).isEqualTo(7L);
        int notes = noteCount(fixture.caseId());
        int audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE case_id = ? AND action = 'CASE_NOTE_CREATED'",
                Integer.class, fixture.caseId()
        );
        assertThat(audits).isEqualTo(notes);
        assertThat(notes).isBetween(0, 1);
        List<String> auditActions = jdbc.queryForList(
                "SELECT action FROM audit_log WHERE case_id = ? ORDER BY id",
                String.class, fixture.caseId()
        );
        if (jdbc.queryForObject(
                "SELECT case_status = 'CLOSED' FROM fraud_case WHERE id = ?",
                Boolean.class, fixture.id()
        )) {
            assertThat(notes).as("no note may write-skew after CLOSED").isZero();
        }
        if (kind == RaceKind.NOTE) {
            assertThat(notes).isEqualTo(1);
            assertThat(audits).isEqualTo(1);
            assertThat(auditActions).containsExactly("CASE_NOTE_CREATED");
            assertNoteTimestampMatchesParent(fixture);
            return;
        }
        if (noteResult.success()) {
            assertThat(notes).isEqualTo(1);
            assertThat(audits).isEqualTo(1);
            assertThat(auditActions).containsExactly("CASE_NOTE_CREATED");
            assertNoteTimestampMatchesParent(fixture);
            assertUnchangedReviewFields(fixture);
            return;
        }
        assertThat(otherResult.success()).isTrue();
        assertThat(notes).isZero();
        assertThat(audits).isZero();
        assertThat(auditActions).containsExactly(kind.auditAction());
        assertOtherWinnerFields(kind, fixture);
    }

    private void assertUnchangedReviewFields(CaseFixture fixture) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT case_status, final_disposition, assignee_ref FROM fraud_case WHERE id = ?",
                fixture.id()
        );
        assertThat(row.get("case_status")).isEqualTo("IN_REVIEW");
        assertThat(row.get("final_disposition")).isNull();
        assertThat(row.get("assignee_ref")).isEqualTo(ASSIGNEE);
    }

    private void assertOtherWinnerFields(RaceKind kind, CaseFixture fixture) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT case_status, final_disposition, assignee_ref FROM fraud_case WHERE id = ?",
                fixture.id()
        );
        switch (kind) {
            case RESOLUTION -> {
                assertThat(row.get("case_status")).isEqualTo("CLOSED");
                assertThat(row.get("final_disposition")).isEqualTo("CONFIRMED_FRAUD");
                assertThat(row.get("assignee_ref")).isEqualTo(ASSIGNEE);
            }
            case STATUS -> {
                assertThat(row.get("case_status"))
                        .isEqualTo("ADDITIONAL_INFORMATION_REQUIRED");
                assertThat(row.get("final_disposition")).isNull();
                assertThat(row.get("assignee_ref")).isEqualTo(ASSIGNEE);
            }
            case ASSIGNEE -> {
                assertThat(row.get("case_status")).isEqualTo("IN_REVIEW");
                assertThat(row.get("final_disposition")).isNull();
                assertThat(row.get("assignee_ref"))
                        .isEqualTo("30000000-0000-4000-8000-000000000003");
            }
            case NOTE -> throw new AssertionError("NOTE is handled separately");
        }
    }

    private Callable<CommandOutcome> contender(
            CaseFixture fixture,
            ServiceMethod method
    ) {
        return () -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .isNull();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new TestingAuthenticationToken(
                    "concurrency-contender",
                    null,
                    authorityFor(method)
            ));
            SecurityContextHolder.setContext(context);
            long threadId = Thread.currentThread().getId();
            activeServiceMethod.set(method);
            try {
                switch (method) {
                    case NOTE_CREATE -> service.create(
                            new FraudCaseNoteCommand.Create(
                                    fixture.caseId(), "race memo", 6
                            ),
                            "trace_note_race_01"
                    );
                    case RESOLUTION -> workflowService.resolve(
                            new FraudCaseWorkflowCommand.Resolution(
                                    fixture.caseId(),
                                    "CONFIRMED_FRAUD",
                                    "CASE_RESOLUTION_COMPLETED",
                                    6
                            ),
                            "trace_note_resolution_race_01"
                    );
                    case STATUS_CHANGE -> workflowService.changeStatus(
                            new FraudCaseWorkflowCommand.StatusChange(
                                    fixture.caseId(),
                                    FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                                    false,
                                    null,
                                    AuditReasonCode
                                            .CASE_ADDITIONAL_INFORMATION_REQUESTED,
                                    6
                            ),
                            "trace_note_status_race_01"
                    );
                    case ASSIGNEE_CHANGE -> workflowService.changeAssignee(
                            new FraudCaseWorkflowCommand.AssigneeChange(
                                    fixture.caseId(),
                                    "30000000-0000-4000-8000-000000000003",
                                    AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                                    6
                            ),
                            "trace_note_assignee_race_01"
                    );
                }
                return CommandOutcome.success(method, threadId);
            } catch (InvestigationNoteException exception) {
                if (exception.getReason()
                        != InvestigationNoteException.Reason
                        .CONCURRENT_MODIFICATION) {
                    throw exception;
                }
                return CommandOutcome.conflict(
                        method, threadId, exception.getClass(),
                        exception.getReason().name(),
                        hasOptimisticCause(exception)
                );
            } catch (FraudCaseWorkflowException exception) {
                if (exception.getReason()
                        != FraudCaseWorkflowException.Reason
                        .CONCURRENT_MODIFICATION) {
                    throw exception;
                }
                return CommandOutcome.conflict(
                        method, threadId, exception.getClass(),
                        exception.getReason().name(),
                        hasOptimisticCause(exception)
                );
            } finally {
                activeServiceMethod.remove();
                SecurityContextHolder.clearContext();
                assertThat(SecurityContextHolder.getContext()
                        .getAuthentication()).isNull();
            }
        };
    }

    private String authorityFor(ServiceMethod method) {
        return switch (method) {
            case NOTE_CREATE -> CASE_NOTE_WRITE;
            case RESOLUTION -> CASE_RESOLUTION_WRITE;
            case STATUS_CHANGE, ASSIGNEE_CHANGE -> CASE_WORKFLOW_WRITE;
        };
    }

    private ReadBarrier installReadBarrier(UUID caseId) {
        ReadBarrier barrier = new ReadBarrier(
                new CountDownLatch(2),
                new CountDownLatch(1),
                ConcurrentHashMap.newKeySet()
        );
        FraudCaseRepository barrierRepository = (FraudCaseRepository)
                Proxy.newProxyInstance(
                        FraudCaseRepository.class.getClassLoader(),
                        new Class<?>[]{FraudCaseRepository.class},
                        (proxy, method, arguments) -> {
                            Object result;
                            try {
                                result = method.invoke(
                                        fraudCaseRepository, arguments
                                );
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                            if ("findByCaseId".equals(method.getName())
                                    && arguments != null
                                    && arguments.length == 1
                                    && caseId.equals(arguments[0])) {
                                ServiceMethod serviceMethod =
                                        activeServiceMethod.get();
                                if (serviceMethod == null) {
                                    throw new AssertionError(
                                            "production service method must be recorded"
                                    );
                                }
                                boolean transactionActive =
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive();
                                FraudCase loaded = ((Optional<?>) result)
                                        .filter(FraudCase.class::isInstance)
                                        .map(FraudCase.class::cast)
                                        .orElseThrow();
                                barrier.evidence().add(new ReadEvidence(
                                        serviceMethod,
                                        Thread.currentThread().getId(),
                                        loaded.getConcurrencyVersion(),
                                        transactionActive
                                ));
                                barrier.loaded().countDown();
                                await(barrier.loaded());
                                await(barrier.release());
                            }
                            return result;
                        }
                );
        ReflectionTestUtils.setField(
                service, "fraudCaseRepository", barrierRepository
        );
        ReflectionTestUtils.setField(
                workflowService, "fraudCaseRepository", barrierRepository
        );
        assertThat(ReflectionTestUtils.getField(
                service, "fraudCaseRepository"
        )).isSameAs(barrierRepository);
        assertThat(ReflectionTestUtils.getField(
                workflowService, "fraudCaseRepository"
        )).isSameAs(barrierRepository);
        return barrier;
    }

    private void restoreServiceRepositories() {
        ReflectionTestUtils.setField(
                service, "fraudCaseRepository", fraudCaseRepository
        );
        ReflectionTestUtils.setField(
                workflowService, "fraudCaseRepository", fraudCaseRepository
        );
    }

    private void assertReadEvidence(ReadBarrier barrier, RaceKind kind) {
        assertThat(barrier.evidence()).hasSize(2);
        assertThat(barrier.evidence())
                .extracting(ReadEvidence::transactionActive)
                .containsOnly(true);
        assertThat(barrier.evidence())
                .extracting(ReadEvidence::threadId)
                .doesNotHaveDuplicates();
        assertThat(barrier.evidence())
                .extracting(ReadEvidence::loadedVersion)
                .containsOnly(6L);
        if (kind == RaceKind.NOTE) {
            assertThat(barrier.evidence())
                    .extracting(ReadEvidence::serviceMethod)
                    .containsOnly(ServiceMethod.NOTE_CREATE);
        } else {
            assertThat(barrier.evidence())
                    .extracting(ReadEvidence::serviceMethod)
                    .containsExactlyInAnyOrder(
                            ServiceMethod.NOTE_CREATE,
                            kind.serviceMethod()
                    );
        }
    }

    private void assertOneSuccessAndOneProductionConflict(
            CommandOutcome first,
            CommandOutcome second
    ) {
        List<CommandOutcome> outcomes = List.of(first, second);
        assertThat(outcomes).filteredOn(CommandOutcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(result -> !result.success()).hasSize(1);
        CommandOutcome loser = outcomes.stream()
                .filter(result -> !result.success())
                .findFirst()
                .orElseThrow();
        assertThat(loser.errorCode()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(loser.optimisticCause()).isTrue();
        Class<? extends RuntimeException> expectedType =
                loser.serviceMethod() == ServiceMethod.NOTE_CREATE
                        ? InvestigationNoteException.class
                        : FraudCaseWorkflowException.class;
        assertThat(loser.exceptionType()).isEqualTo(expectedType);
        assertThat(outcomes)
                .extracting(CommandOutcome::threadId)
                .doesNotHaveDuplicates();
    }

    private void assertNoteTimestampMatchesParent(CaseFixture fixture) {
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(note.created_at = fraud_case.last_changed_at)
                FROM investigation_note note
                JOIN fraud_case fraud_case
                  ON fraud_case.id = note.fraud_case_id
                WHERE fraud_case.id = ?
                """, Boolean.class, fixture.id())).isTrue();
    }

    private List<InvestigationNoteListResponse> listPages(
            UUID caseId,
            FraudCaseNoteCommand.Direction direction
    ) {
        List<InvestigationNoteListResponse> pages = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            pages.add(service.list(
                    new FraudCaseNoteCommand.ListQuery(
                            caseId, page, 3, direction
                    ),
                    "trace_note_cross_page_" + direction + "_" + page
            ));
        }
        return pages;
    }

    private List<UUID> noteIds(
            List<InvestigationNoteListResponse> pages
    ) {
        return pages.stream()
                .flatMap(page -> page.items().stream())
                .map(item -> item.noteId())
                .toList();
    }

    private void assertPageMetadata(
            List<InvestigationNoteListResponse> pages
    ) {
        assertThat(pages).hasSize(3);
        for (int number = 0; number < pages.size(); number++) {
            var metadata = pages.get(number).page();
            assertThat(metadata.number()).isEqualTo(number);
            assertThat(metadata.size()).isEqualTo(3);
            assertThat(metadata.totalElements()).isEqualTo(7);
            assertThat(metadata.totalPages()).isEqualTo(3);
            assertThat(metadata.first()).isEqualTo(number == 0);
            assertThat(metadata.last()).isEqualTo(number == 2);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("race barrier timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("race barrier interrupted", exception);
        }
    }

    private boolean hasOptimisticCause(Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            if (current instanceof OptimisticLockException
                    || current instanceof OptimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private CaseFixture insertReviewCase(long version) {
        UUID caseId = UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fraud_case
                    (case_id, case_status, final_disposition, assignee_ref,
                     review_started_at, closed_at, concurrency_version,
                     created_at, last_changed_at)
                VALUES (?, 'IN_REVIEW', NULL, ?, ?, NULL, ?, ?, ?)
                RETURNING id
                """, Long.class, caseId, ASSIGNEE, Timestamp.from(CREATED_AT), version,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        return new CaseFixture(id, caseId);
    }

    private UUID insertNote(long casePk, String content, Instant createdAt) {
        UUID noteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO investigation_note
                    (note_id, fraud_case_id, author_type, author_ref, content, created_at)
                VALUES (?, ?, 'SYSTEM', 'finguardops-backend', ?, ?)
                """, noteId, casePk, content, Timestamp.from(createdAt));
        return noteId;
    }

    private void insertNoteAudit(UUID caseId, String metadata) {
        jdbc.update("""
                INSERT INTO audit_log
                    (audit_id, actor_type, actor_id, action, reason_code, target_type,
                     target_id, transaction_id, case_id, trace_id,
                     before_value_summary, after_value_summary, metadata, changed_at)
                VALUES (?, 'SYSTEM', 'finguardops-backend', 'CASE_NOTE_CREATED',
                    'CASE_INVESTIGATION_NOTE_ADDED', 'FRAUD_CASE', ?, NULL, ?,
                    'trace_note_db_01', NULL, NULL, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), caseId, caseId, metadata, Timestamp.from(CREATED_AT));
    }

    private int noteCount(UUID caseId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM investigation_note note
                JOIN fraud_case fraud_case ON fraud_case.id = note.fraud_case_id
                WHERE fraud_case.case_id = ?
                """, Integer.class, caseId);
    }

    private Instant lastChangedAt(long id) {
        return jdbc.queryForObject(
                "SELECT last_changed_at FROM fraud_case WHERE id = ?",
                Timestamp.class, id
        ).toInstant();
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position
                """, String.class, table);
    }

    private List<String> constraints(String table) {
        return jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = CAST(? AS regclass) ORDER BY conname
                """, String.class, table);
    }

    private List<String> indexes(String table) {
        return jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname='public' AND tablename=? ORDER BY indexname
                """, String.class, table);
    }

    private enum RaceKind {
        NOTE(ServiceMethod.NOTE_CREATE, "CASE_NOTE_CREATED"),
        RESOLUTION(ServiceMethod.RESOLUTION, "CASE_RESOLVED"),
        STATUS(ServiceMethod.STATUS_CHANGE, "CASE_STATUS_CHANGED"),
        ASSIGNEE(ServiceMethod.ASSIGNEE_CHANGE, "CASE_ASSIGNEE_CHANGED");

        private final ServiceMethod serviceMethod;
        private final String auditAction;

        RaceKind(ServiceMethod serviceMethod, String auditAction) {
            this.serviceMethod = serviceMethod;
            this.auditAction = auditAction;
        }

        ServiceMethod serviceMethod() {
            return serviceMethod;
        }

        String auditAction() {
            return auditAction;
        }
    }

    private enum ServiceMethod {
        NOTE_CREATE,
        RESOLUTION,
        STATUS_CHANGE,
        ASSIGNEE_CHANGE
    }

    private record CommandOutcome(
            ServiceMethod serviceMethod,
            boolean success,
            Class<? extends RuntimeException> exceptionType,
            String errorCode,
            boolean optimisticCause,
            long threadId
    ) {
        static CommandOutcome success(
                ServiceMethod serviceMethod,
                long threadId
        ) {
            return new CommandOutcome(
                    serviceMethod, true, null, null, false, threadId
            );
        }

        static CommandOutcome conflict(
                ServiceMethod serviceMethod,
                long threadId,
                Class<? extends RuntimeException> exceptionType,
                String errorCode,
                boolean optimisticCause
        ) {
            return new CommandOutcome(
                    serviceMethod, false, exceptionType, errorCode,
                    optimisticCause, threadId
            );
        }
    }

    private record ReadEvidence(
            ServiceMethod serviceMethod,
            long threadId,
            long loadedVersion,
            boolean transactionActive
    ) { }

    private record ReadBarrier(
            CountDownLatch loaded,
            CountDownLatch release,
            Set<ReadEvidence> evidence
    ) { }

    private record CaseFixture(long id, UUID caseId) { }
}
