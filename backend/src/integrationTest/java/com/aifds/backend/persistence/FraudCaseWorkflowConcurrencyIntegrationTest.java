package com.aifds.backend.persistence;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseWorkflowException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FraudCaseWorkflowConcurrencyIntegrationTest
        extends PostgresqlIntegrationTestSupport {

    private static final String FIRST_ASSIGNEE =
            "10000000-0000-4000-9000-000000000001";
    private static final String SECOND_ASSIGNEE =
            "20000000-0000-4000-9000-000000000002";

    @Autowired
    private FraudCaseWorkflowService service;

    @Autowired
    private FraudCaseRepository repository;

    @Autowired
    private AuditLogPersistenceService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sameExpectedVersionHasExactlyOneSuccessAndOneConflict()
            throws Exception {
        UUID caseId = insertOpenCase();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> invokeService(
                    caseId,
                    FIRST_ASSIGNEE,
                    ready,
                    start
            ));
            Future<Outcome> second = executor.submit(() -> invokeService(
                    caseId,
                    SECOND_ASSIGNEE,
                    ready,
                    start
            ));
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(
                    Outcome.SUCCESS,
                    Outcome.CONCURRENT_MODIFICATION
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        assertThat(caseAssignee(caseId))
                .isIn(FIRST_ASSIGNEE, SECOND_ASSIGNEE);
    }

    @Test
    void optimisticFlushLoserRollsBackAndWinnerKeepsSingleAudit()
            throws Exception {
        UUID caseId = insertOpenCase();
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch flush = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> invokeRawTransaction(
                    caseId,
                    FIRST_ASSIGNEE,
                    loaded,
                    flush
            ));
            Future<Boolean> second = executor.submit(() -> invokeRawTransaction(
                    caseId,
                    SECOND_ASSIGNEE,
                    loaded,
                    flush
            ));
            assertThat(loaded.await(20, TimeUnit.SECONDS)).isTrue();
            flush.countDown();

            int successes = 0;
            int optimisticConflicts = 0;
            for (Future<Boolean> future : List.of(first, second)) {
                try {
                    if (future.get(30, TimeUnit.SECONDS)) {
                        successes++;
                    }
                } catch (ExecutionException exception) {
                    if (hasCause(
                            exception,
                            ObjectOptimisticLockingFailureException.class
                    )) {
                        optimisticConflicts++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(optimisticConflicts).isEqualTo(1);
        } finally {
            flush.countDown();
            executor.shutdownNow();
        }

        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
    }

    @Test
    void statusAndAssigneeCommandsCompeteFromSameInReviewVersion()
            throws Exception {
        UUID caseId = insertCase(
                FraudCaseStatus.IN_REVIEW,
                FIRST_ASSIGNEE
        );
        ReadBarrier barrier = installReadBarrier(caseId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CommandResult statusResult;
        CommandResult assigneeResult;
        try {
            Future<CommandResult> status = executor.submit(() -> invokeStatus(
                    new FraudCaseWorkflowCommand.StatusChange(
                            caseId,
                            FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                            false,
                            null,
                            AuditReasonCode
                                    .CASE_ADDITIONAL_INFORMATION_REQUESTED,
                            0L
                    )
            ));
            Future<CommandResult> assignee = executor.submit(
                    () -> invokeAssignee(
                            new FraudCaseWorkflowCommand.AssigneeChange(
                                    caseId,
                                    SECOND_ASSIGNEE,
                                    AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                                    0L
                            )
                    )
            );
            assertThat(barrier.loaded().await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(barrier.threadIds()).hasSize(2);
            barrier.release().countDown();
            statusResult = status.get(30, TimeUnit.SECONDS);
            assigneeResult = assignee.get(30, TimeUnit.SECONDS);
        } finally {
            barrier.release().countDown();
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
            restoreServiceRepository();
            assertThat(terminated).isTrue();
        }

        assertOneWinner(statusResult, assigneeResult);
        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        if (statusResult == CommandResult.SUCCESS) {
            assertThat(caseState(caseId)).containsEntry(
                    "case_status",
                    FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED.name()
            ).containsEntry("assignee_ref", FIRST_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_STATUS_CHANGED:"
                            + "CASE_ADDITIONAL_INFORMATION_REQUESTED"
            );
        } else {
            assertThat(caseState(caseId))
                    .containsEntry("case_status", "IN_REVIEW")
                    .containsEntry("assignee_ref", SECOND_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_ASSIGNEE_CHANGED:CASE_ASSIGNEE_CHANGED"
            );
        }
    }

    @Test
    void releaseAndResumeCommandsCompeteFromSameAdditionalInfoVersion()
            throws Exception {
        UUID caseId = insertCase(
                FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                FIRST_ASSIGNEE
        );
        ReadBarrier barrier = installReadBarrier(caseId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CommandResult releaseResult;
        CommandResult resumeResult;
        try {
            Future<CommandResult> release = executor.submit(
                    () -> invokeAssignee(
                            new FraudCaseWorkflowCommand.AssigneeChange(
                                    caseId,
                                    null,
                                    AuditReasonCode.CASE_ASSIGNEE_RELEASED,
                                    0L
                            )
                    )
            );
            Future<CommandResult> resume = executor.submit(() -> invokeStatus(
                    new FraudCaseWorkflowCommand.StatusChange(
                            caseId,
                            FraudCaseStatus.IN_REVIEW,
                            false,
                            null,
                            AuditReasonCode.CASE_REVIEW_RESUMED,
                            0L
                    )
            ));
            assertThat(barrier.loaded().await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(barrier.threadIds()).hasSize(2);
            barrier.release().countDown();
            releaseResult = release.get(30, TimeUnit.SECONDS);
            resumeResult = resume.get(30, TimeUnit.SECONDS);
        } finally {
            barrier.release().countDown();
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
            restoreServiceRepository();
            assertThat(terminated).isTrue();
        }

        assertOneWinner(releaseResult, resumeResult);
        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        if (releaseResult == CommandResult.SUCCESS) {
            assertThat(caseState(caseId)).containsEntry(
                    "case_status",
                    FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED.name()
            ).containsEntry("assignee_ref", null);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_ASSIGNEE_CHANGED:CASE_ASSIGNEE_RELEASED"
            );
        } else {
            assertThat(caseState(caseId))
                    .containsEntry("case_status", "IN_REVIEW")
                    .containsEntry("assignee_ref", FIRST_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_STATUS_CHANGED:CASE_REVIEW_RESUMED"
            );
        }
    }

    @RepeatedTest(5)
    void resolutionCompetitionSuiteHasOneWinnerAndOneConflict()
            throws Exception {
        assertResolveVersusResolve(false);
        assertResolveVersusResolve(true);
        assertResolveVersusStatus();
        assertResolveVersusAssignee();
    }

    private void assertResolveVersusResolve(boolean differentDisposition)
            throws Exception {
        UUID caseId = insertCase(
                FraudCaseStatus.IN_REVIEW,
                FIRST_ASSIGNEE
        );
        String secondDisposition = differentDisposition
                ? "FALSE_POSITIVE"
                : "CONFIRMED_FRAUD";
        RaceResult race = compete(
                caseId,
                () -> invokeResolution(resolutionCommand(
                        caseId,
                        "CONFIRMED_FRAUD"
                )),
                () -> invokeResolution(resolutionCommand(
                        caseId,
                        secondDisposition
                ))
        );

        assertOneWinner(race.first(), race.second());
        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        assertThat(caseState(caseId))
                .containsEntry("case_status", "CLOSED")
                .containsEntry("assignee_ref", FIRST_ASSIGNEE);
        String expectedDisposition = race.first() == CommandResult.SUCCESS
                ? "CONFIRMED_FRAUD"
                : secondDisposition;
        assertThat(caseState(caseId))
                .containsEntry("final_disposition", expectedDisposition);
        assertThat(auditActions(caseId)).containsExactly(
                "CASE_RESOLVED:CASE_RESOLUTION_COMPLETED"
        );
        assertResolutionAudit(caseId, expectedDisposition, FIRST_ASSIGNEE);
    }

    private void assertResolveVersusStatus() throws Exception {
        UUID caseId = insertCase(
                FraudCaseStatus.IN_REVIEW,
                FIRST_ASSIGNEE
        );
        RaceResult race = compete(
                caseId,
                () -> invokeResolution(resolutionCommand(
                        caseId,
                        "NORMAL"
                )),
                () -> invokeStatus(new FraudCaseWorkflowCommand.StatusChange(
                        caseId,
                        FraudCaseStatus.ADDITIONAL_INFORMATION_REQUIRED,
                        false,
                        null,
                        AuditReasonCode
                                .CASE_ADDITIONAL_INFORMATION_REQUESTED,
                        0L
                ))
        );

        assertOneWinner(race.first(), race.second());
        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        if (race.first() == CommandResult.SUCCESS) {
            assertThat(caseState(caseId))
                    .containsEntry("case_status", "CLOSED")
                    .containsEntry("final_disposition", "NORMAL")
                    .containsEntry("assignee_ref", FIRST_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_RESOLVED:CASE_RESOLUTION_COMPLETED"
            );
            assertResolutionAudit(caseId, "NORMAL", FIRST_ASSIGNEE);
        } else {
            assertThat(caseState(caseId))
                    .containsEntry(
                            "case_status",
                            "ADDITIONAL_INFORMATION_REQUIRED"
                    )
                    .containsEntry("final_disposition", null)
                    .containsEntry("assignee_ref", FIRST_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_STATUS_CHANGED:"
                            + "CASE_ADDITIONAL_INFORMATION_REQUESTED"
            );
        }
    }

    private void assertResolveVersusAssignee() throws Exception {
        UUID caseId = insertCase(
                FraudCaseStatus.IN_REVIEW,
                FIRST_ASSIGNEE
        );
        RaceResult race = compete(
                caseId,
                () -> invokeResolution(resolutionCommand(
                        caseId,
                        "FALSE_POSITIVE"
                )),
                () -> invokeAssignee(
                        new FraudCaseWorkflowCommand.AssigneeChange(
                                caseId,
                                SECOND_ASSIGNEE,
                                AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                                0L
                        )
                )
        );

        assertOneWinner(race.first(), race.second());
        assertThat(caseVersion(caseId)).isEqualTo(1L);
        assertThat(auditCount(caseId)).isEqualTo(1);
        if (race.first() == CommandResult.SUCCESS) {
            assertThat(caseState(caseId))
                    .containsEntry("case_status", "CLOSED")
                    .containsEntry("final_disposition", "FALSE_POSITIVE")
                    .containsEntry("assignee_ref", FIRST_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_RESOLVED:CASE_RESOLUTION_COMPLETED"
            );
            assertResolutionAudit(
                    caseId,
                    "FALSE_POSITIVE",
                    FIRST_ASSIGNEE
            );
        } else {
            assertThat(caseState(caseId))
                    .containsEntry("case_status", "IN_REVIEW")
                    .containsEntry("final_disposition", null)
                    .containsEntry("assignee_ref", SECOND_ASSIGNEE);
            assertThat(auditActions(caseId)).containsExactly(
                    "CASE_ASSIGNEE_CHANGED:CASE_ASSIGNEE_CHANGED"
            );
        }
    }

    private RaceResult compete(
            UUID caseId,
            Callable<CommandResult> firstCommand,
            Callable<CommandResult> secondCommand
    ) throws Exception {
        ReadBarrier barrier = installReadBarrier(caseId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CommandResult> first = executor.submit(firstCommand);
            Future<CommandResult> second = executor.submit(secondCommand);
            assertThat(barrier.loaded().await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(barrier.threadIds()).hasSize(2);
            barrier.release().countDown();
            return new RaceResult(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        } finally {
            barrier.release().countDown();
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
            restoreServiceRepository();
            assertThat(terminated).isTrue();
        }
    }

    private Outcome invokeService(
            UUID caseId,
            String assignee,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return withAuthority(CASE_WORKFLOW_WRITE, () -> {
            ready.countDown();
            await(start);
            try {
                service.changeStatus(
                        new FraudCaseWorkflowCommand.StatusChange(
                                caseId,
                                FraudCaseStatus.IN_REVIEW,
                                true,
                                assignee,
                                AuditReasonCode.CASE_REVIEW_STARTED,
                                0L
                        ),
                        "trace_case_concurrency_01"
                );
                return Outcome.SUCCESS;
            } catch (FraudCaseWorkflowException exception) {
                if (exception.getReason()
                        == FraudCaseWorkflowException.Reason
                        .CONCURRENT_MODIFICATION) {
                    return Outcome.CONCURRENT_MODIFICATION;
                }
                throw exception;
            }
        });
    }

    private CommandResult invokeStatus(
            FraudCaseWorkflowCommand.StatusChange command
    ) {
        return withAuthority(CASE_WORKFLOW_WRITE, () -> {
            try {
                service.changeStatus(command, "trace_case_cross_race_01");
                return CommandResult.SUCCESS;
            } catch (FraudCaseWorkflowException exception) {
                if (exception.getReason()
                        == FraudCaseWorkflowException.Reason
                        .CONCURRENT_MODIFICATION) {
                    return CommandResult.CONCURRENT_MODIFICATION;
                }
                throw exception;
            }
        });
    }

    private CommandResult invokeAssignee(
            FraudCaseWorkflowCommand.AssigneeChange command
    ) {
        return withAuthority(CASE_WORKFLOW_WRITE, () -> {
            try {
                service.changeAssignee(command, "trace_case_cross_race_01");
                return CommandResult.SUCCESS;
            } catch (FraudCaseWorkflowException exception) {
                if (exception.getReason()
                        == FraudCaseWorkflowException.Reason
                        .CONCURRENT_MODIFICATION) {
                    return CommandResult.CONCURRENT_MODIFICATION;
                }
                throw exception;
            }
        });
    }

    private CommandResult invokeResolution(
            FraudCaseWorkflowCommand.Resolution command
    ) {
        return withAuthority(CASE_RESOLUTION_WRITE, () -> {
            try {
                service.resolve(command, "trace_case_resolution_race_01");
                return CommandResult.SUCCESS;
            } catch (FraudCaseWorkflowException exception) {
                if (exception.getReason()
                        == FraudCaseWorkflowException.Reason
                        .CONCURRENT_MODIFICATION) {
                    return CommandResult.CONCURRENT_MODIFICATION;
                }
                throw exception;
            }
        });
    }

    private <T> T withAuthority(String authority, Supplier<T> action) {
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(
                "concurrency-contender",
                null,
                authority
        ));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .isNull();
        }
    }

    private FraudCaseWorkflowCommand.Resolution resolutionCommand(
            UUID caseId,
            String disposition
    ) {
        return new FraudCaseWorkflowCommand.Resolution(
                caseId,
                disposition,
                "CASE_RESOLUTION_COMPLETED",
                0L
        );
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
                                result = method.invoke(repository, arguments);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                            if ("findByCaseId".equals(method.getName())
                                    && arguments != null
                                    && arguments.length == 1
                                    && caseId.equals(arguments[0])) {
                                if (!TransactionSynchronizationManager
                                        .isActualTransactionActive()) {
                                    throw new AssertionError(
                                            "workflow read must be transactional"
                                    );
                                }
                                Object loaded = ((Optional<?>) result)
                                        .orElseThrow();
                                if (!(loaded instanceof FraudCase loadedCase)) {
                                    throw new AssertionError(
                                            "workflow read must return a case"
                                    );
                                }
                                if (loadedCase.getConcurrencyVersion() != 0L) {
                                    throw new AssertionError(
                                            "both commands must load version zero"
                                    );
                                }
                                barrier.threadIds().add(
                                        Thread.currentThread().getId()
                                );
                                barrier.loaded().countDown();
                                await(barrier.loaded());
                                await(barrier.release());
                            }
                            return result;
                        }
                );
        ReflectionTestUtils.setField(
                service,
                "fraudCaseRepository",
                barrierRepository
        );
        return barrier;
    }

    private void restoreServiceRepository() {
        ReflectionTestUtils.setField(
                service,
                "fraudCaseRepository",
                repository
        );
    }

    private void assertOneWinner(
            CommandResult first,
            CommandResult second
    ) {
        assertThat(List.of(first, second)).containsExactlyInAnyOrder(
                CommandResult.SUCCESS,
                CommandResult.CONCURRENT_MODIFICATION
        );
    }

    private boolean invokeRawTransaction(
            UUID caseId,
            String assignee,
            CountDownLatch loaded,
            CountDownLatch flush
    ) {
        return Boolean.TRUE.equals(
                new TransactionTemplate(transactionManager).execute(status -> {
                    FraudCase fraudCase = repository.findByCaseId(caseId)
                            .orElseThrow();
                    if (fraudCase.getConcurrencyVersion() != 0L) {
                        throw new IllegalStateException(
                                "test expected initial version"
                        );
                    }
                    ObjectNode before = objectMapper.createObjectNode()
                            .put("caseStatus", "OPEN");
                    fraudCase.startReview(
                            assignee,
                            Instant.now().truncatedTo(ChronoUnit.MICROS)
                    );
                    loaded.countDown();
                    await(flush);
                    repository.flush();
                    ObjectNode after = objectMapper.createObjectNode()
                            .put("caseStatus", "IN_REVIEW")
                            .put("assigneeRef", assignee);
                    auditService.append(new AuditLogDraft(
                            AuditActorType.SYSTEM,
                            AuditLog.SYSTEM_ACTOR_ID,
                            AuditAction.CASE_STATUS_CHANGED,
                            AuditReasonCode.CASE_REVIEW_STARTED,
                            AuditTargetType.FRAUD_CASE,
                            caseId,
                            null,
                            caseId,
                            "trace_case_optimistic_01",
                            before,
                            after,
                            objectMapper.createObjectNode()
                    ));
                    return true;
                })
        );
    }

    private UUID insertOpenCase() {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = Instant.now()
                .minusSeconds(1)
                .truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, 'OPEN', 0, ?, ?)
                        """,
                caseId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
        return caseId;
    }

    private UUID insertCase(
            FraudCaseStatus status,
            String assigneeRef
    ) {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = Instant.now()
                .minusSeconds(2)
                .truncatedTo(ChronoUnit.MICROS);
        Instant reviewStartedAt = createdAt.plusSeconds(1);
        jdbcTemplate.update(
                """
                        INSERT INTO fraud_case (
                            case_id, case_status, assignee_ref,
                            review_started_at, concurrency_version,
                            created_at, last_changed_at
                        ) VALUES (?, ?, ?, ?, 0, ?, ?)
                        """,
                caseId,
                status.name(),
                assigneeRef,
                Timestamp.from(reviewStartedAt),
                Timestamp.from(createdAt),
                Timestamp.from(reviewStartedAt)
        );
        return caseId;
    }

    private long caseVersion(UUID caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT concurrency_version FROM fraud_case WHERE case_id = ?",
                Long.class,
                caseId
        );
    }

    private String caseAssignee(UUID caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT assignee_ref FROM fraud_case WHERE case_id = ?",
                String.class,
                caseId
        );
    }

    private int auditCount(UUID caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE case_id = ?",
                Integer.class,
                caseId
        );
    }

    private java.util.Map<String, Object> caseState(UUID caseId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT case_status, final_disposition, assignee_ref,
                               closed_at
                        FROM fraud_case
                        WHERE case_id = ?
                        """,
                caseId
        );
    }

    private List<String> auditActions(UUID caseId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT action || ':' || reason_code
                        FROM audit_log
                        WHERE case_id = ?
                        ORDER BY id
                        """,
                String.class,
                caseId
        );
    }

    private void assertResolutionAudit(
            UUID caseId,
            String disposition,
            String assigneeRef
    ) {
        java.util.Map<String, Object> audit = jdbcTemplate.queryForMap(
                """
                        SELECT actor_type, actor_id, target_id,
                               transaction_id, case_id,
                               before_value_summary = jsonb_build_object(
                                   'caseStatus', 'IN_REVIEW',
                                   'assigneeRef', ?::text
                               ) AS before_exact,
                               after_value_summary = jsonb_build_object(
                                   'caseStatus', 'CLOSED',
                                   'finalDisposition', ?::text,
                                   'assigneeRef', ?::text
                               ) AS after_exact,
                               metadata = '{}'::jsonb AS metadata_empty
                        FROM audit_log
                        WHERE case_id = ? AND action = 'CASE_RESOLVED'
                        """,
                assigneeRef,
                disposition,
                assigneeRef,
                caseId
        );
        assertThat(audit)
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("actor_id", "finguardops-backend")
                .containsEntry("target_id", caseId)
                .containsEntry("transaction_id", null)
                .containsEntry("case_id", caseId)
                .containsEntry("before_exact", true)
                .containsEntry("after_exact", true)
                .containsEntry("metadata_empty", true);
    }

    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type
    ) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", exception);
        }
    }

    private enum Outcome {
        SUCCESS,
        CONCURRENT_MODIFICATION
    }

    private enum CommandResult {
        SUCCESS,
        CONCURRENT_MODIFICATION
    }

    private record ReadBarrier(
            CountDownLatch loaded,
            CountDownLatch release,
            Set<Long> threadIds
    ) {
    }

    private record RaceResult(
            CommandResult first,
            CommandResult second
    ) {
    }
}
