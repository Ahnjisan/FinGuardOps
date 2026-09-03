package com.aifds.backend.security.method;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.exception.FraudCaseNotFoundException;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.repository.InvestigationNoteRepository;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.service.InvestigationNoteService;
import com.aifds.backend.security.principal.FinGuardOpsAuthenticationToken;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc."
                        + "DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa."
                        + "HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway."
                        + "FlywayAutoConfiguration"
        }
)
@Import(HighRiskWriteMethodSecurityIntegrationTest.TransactionConfiguration.class)
class HighRiskWriteMethodSecurityIntegrationTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001"
    );
    private static final UUID USER_SUBJECT = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );

    @Autowired
    private FraudCaseWorkflowService workflowService;

    @Autowired
    private InvestigationNoteService noteService;

    @Autowired
    private CountingTransactionManager transactionManager;

    @MockitoBean
    private FraudCaseRepository fraudCaseRepository;

    @MockitoBean
    private InvestigationNoteRepository noteRepository;

    @MockitoBean
    private AuditLogPersistenceService auditLogService;

    @BeforeEach
    void resetState() {
        SecurityContextHolder.clearContext();
        transactionManager.reset();
        reset(fraudCaseRepository, noteRepository, auditLogService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesActualProductionServiceProxies() {
        assertThat(AopUtils.isAopProxy(workflowService)).isTrue();
        assertThat(AopUtils.isAopProxy(noteService)).isTrue();
        assertThat(AopUtils.getTargetClass(workflowService))
                .isEqualTo(FraudCaseWorkflowService.class);
        assertThat(AopUtils.getTargetClass(noteService))
                .isEqualTo(InvestigationNoteService.class);
    }

    @Test
    void rejectsAllFourMethodsWithoutAuthenticationBeforeAnyWork() {
        for (Method method : Method.values()) {
            assertThatThrownBy(() -> invoke(method))
                    .as(method.name())
                    .isInstanceOf(
                            AuthenticationCredentialsNotFoundException.class
                    );
        }
        assertDeniedBeforeWork();
    }

    @Test
    void rejectsAllFourMethodsWithInsufficientAuthorityBeforeAnyWork() {
        authenticate("case:read");
        for (Method method : Method.values()) {
            assertThatThrownBy(() -> invoke(method))
                    .as(method.name())
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertDeniedBeforeWork();
    }

    @Test
    void exactAuthorityStartsTransactionAndReachesExistingServiceBody() {
        for (Method method : Method.values()) {
            resetState();
            authenticateUser(method.authority());
            when(fraudCaseRepository.findByCaseId(CASE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> invoke(method))
                    .as(method.name())
                    .isInstanceOf(FraudCaseNotFoundException.class);

            assertThat(transactionManager.begins()).isEqualTo(1);
            verify(fraudCaseRepository).findByCaseId(CASE_ID);
            verifyNoInteractions(noteRepository, auditLogService);
        }
    }

    @Test
    void rejectsTestingAuthenticationTokenWithExactAuthorityInsideBoundary() {
        for (Method method : Method.values()) {
            resetState();
            authenticate(method.authority());

            assertThatThrownBy(() -> invoke(method))
                    .as(method.name())
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("An authenticated USER principal is required");

            assertThat(transactionManager.begins()).isEqualTo(1);
            verifyNoInteractions(
                    fraudCaseRepository,
                    noteRepository,
                    auditLogService
            );
        }
    }

    private void assertDeniedBeforeWork() {
        assertThat(transactionManager.begins()).isZero();
        verifyNoInteractions(
                fraudCaseRepository,
                noteRepository,
                auditLogService
        );
    }

    private void authenticate(String authority) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(
                "method-security-user",
                null,
                authority
        ));
        SecurityContextHolder.setContext(context);
    }

    private void authenticateUser(String authority) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new FinGuardOpsAuthenticationToken(
                new FinGuardOpsPrincipal(
                        USER_SUBJECT,
                        FinGuardOpsPrincipal.Type.USER,
                        Set.of()
                ),
                List.of(new SimpleGrantedAuthority(authority))
        ));
        SecurityContextHolder.setContext(context);
    }

    private void invoke(Method method) {
        switch (method) {
            case STATUS -> workflowService.changeStatus(
                    new FraudCaseWorkflowCommand.StatusChange(
                            CASE_ID,
                            FraudCaseStatus.IN_REVIEW,
                            true,
                            "20000000-0000-4000-8000-000000000002",
                            AuditReasonCode.CASE_REVIEW_STARTED,
                            0
                    ),
                    "trace_method_security_01"
            );
            case ASSIGNEE -> workflowService.changeAssignee(
                    new FraudCaseWorkflowCommand.AssigneeChange(
                            CASE_ID,
                            "20000000-0000-4000-8000-000000000002",
                            AuditReasonCode.CASE_ASSIGNEE_CHANGED,
                            0
                    ),
                    "trace_method_security_01"
            );
            case RESOLUTION -> workflowService.resolve(
                    new FraudCaseWorkflowCommand.Resolution(
                            CASE_ID,
                            "CONFIRMED_FRAUD",
                            "CASE_RESOLUTION_COMPLETED",
                            0
                    ),
                    "trace_method_security_01"
            );
            case NOTE -> noteService.create(
                    new FraudCaseNoteCommand.Create(CASE_ID, "memo", 0),
                    "trace_method_security_01"
            );
        }
    }

    private enum Method {
        STATUS(CASE_WORKFLOW_WRITE),
        ASSIGNEE(CASE_WORKFLOW_WRITE),
        RESOLUTION(CASE_RESOLUTION_WRITE),
        NOTE(CASE_NOTE_WRITE);

        private final String authority;

        Method(String authority) {
            this.authority = authority;
        }

        String authority() {
            return authority;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionConfiguration {

        @Bean(name = "transactionManager")
        CountingTransactionManager countingTransactionManager() {
            return new CountingTransactionManager();
        }
    }

    static class CountingTransactionManager
            extends AbstractPlatformTransactionManager {

        private final AtomicInteger begins = new AtomicInteger();

        int begins() {
            return begins.get();
        }

        void reset() {
            begins.set(0);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
