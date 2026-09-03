package com.aifds.backend.security.web;

import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.service.AuditLogDraft;
import com.aifds.backend.audit.service.AuditLogPersistenceService;
import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.entity.FraudCase;
import com.aifds.backend.fraudcase.entity.FraudCaseStatus;
import com.aifds.backend.fraudcase.entity.InvestigationNote;
import com.aifds.backend.fraudcase.repository.FraudCaseRepository;
import com.aifds.backend.fraudcase.repository.InvestigationNoteRepository;
import com.aifds.backend.security.support.EphemeralRsaJwtFixture;
import com.aifds.backend.security.support.InProcessJwkSetServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc."
                        + "DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa."
                        + "HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway."
                        + "FlywayAutoConfiguration",
                "finguardops.security.insecure-loopback-jwk-allowed=true"
        }
)
@AutoConfigureMockMvc
@Import(UserAuditActorWebIntegrationTest.TransactionConfiguration.class)
class UserAuditActorWebIntegrationTest {

    private static final UUID CASE_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000001"
    );
    private static final String USER_SUBJECT =
            EphemeralRsaJwtFixture.SUBJECT;
    private static final String ASSIGNEE =
            "20000000-0000-4000-8000-000000000002";
    private static final String SECOND_ASSIGNEE =
            "30000000-0000-4000-8000-000000000003";
    private static final EphemeralRsaJwtFixture JWT =
            EphemeralRsaJwtFixture.create("user-audit-actor");

    private static InProcessJwkSetServer jwkServer;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudCaseRepository fraudCaseRepository;

    @MockitoBean
    private InvestigationNoteRepository noteRepository;

    @MockitoBean
    private AuditLogPersistenceService auditLogService;

    @BeforeAll
    static void startJwkServer() {
        jwkServer = InProcessJwkSetServer.start();
        jwkServer.serveKeys(JWT.publicJwk());
    }

    @AfterAll
    static void stopJwkServer() {
        jwkServer.close();
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "finguardops.security.issuer",
                () -> EphemeralRsaJwtFixture.ISSUER
        );
        registry.add(
                "finguardops.security.jwk-set-uri",
                () -> jwkServer.uri().toString()
        );
    }

    @BeforeEach
    void resetCollaborators() {
        reset(fraudCaseRepository, noteRepository, auditLogService);
        when(noteRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void assertRequestContextWasCleared() {
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void signedUserJwtPersistsExactActorForAllFourWrites() throws Exception {
        performStatus();
        assertSingleUserAudit();

        resetCollaborators();
        performAssignee();
        assertSingleUserAudit();

        resetCollaborators();
        performResolution();
        assertSingleUserAudit();

        resetCollaborators();
        performNote();
        AuditLogDraft noteAudit = assertSingleUserAudit();
        var noteCaptor = forClass(InvestigationNote.class);
        verify(noteRepository).saveAndFlush(noteCaptor.capture());
        assertThat(noteCaptor.getValue().getAuthorType().name())
                .isEqualTo("USER");
        assertThat(noteCaptor.getValue().getAuthorRef())
                .isEqualTo(USER_SUBJECT);
        assertThat(noteAudit.actorId())
                .isEqualTo(noteCaptor.getValue().getAuthorRef());
        assertThat(noteAudit.metadata().toString())
                .doesNotContain("signed memo", "roles", "claim", "Bearer");
    }

    @Test
    void rejectsBodyActorSpoofingBeforeProductionService() throws Exception {
        mockMvc.perform(post("/api/v1/cases/{caseId}/notes", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_actor_spoof_01")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType("application/json")
                        .content("""
                                {
                                  "content":"memo",
                                  "expectedVersion":0,
                                  "authorType":"SYSTEM",
                                  "authorRef":"finguardops-backend"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(
                fraudCaseRepository,
                noteRepository,
                auditLogService
        );
    }

    @Test
    void rejectsServiceJwtAndTestingAuthenticationTokenWithoutFallback()
            throws Exception {
        String body = """
                {
                  "targetStatus":"IN_REVIEW",
                  "assigneeRef":"20000000-0000-4000-8000-000000000002",
                  "reasonCode":"CASE_REVIEW_STARTED",
                  "expectedVersion":0
                }
                """;
        String serviceToken = JWT.sign(JWT.validClaims(
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        ));

        mockMvc.perform(patch("/api/v1/cases/{caseId}/status", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_service_deny_01")
                        .header("Authorization", "Bearer " + serviceToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(patch("/api/v1/cases/{caseId}/status", CASE_ID)
                        .with(authentication(new TestingAuthenticationToken(
                                "principal-sentinel",
                                "credential-sentinel",
                                "case:workflow:write"
                        )))
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_testing_deny_01")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value(
                        FinGuardOpsAccessDeniedHandler.ACCESS_DENIED_MESSAGE
                ));

        verifyNoInteractions(
                fraudCaseRepository,
                noteRepository,
                auditLogService
        );
    }

    private void performStatus() throws Exception {
        FraudCase fraudCase = openCase();
        stubCaseAndVersionFlush(fraudCase);
        mockMvc.perform(patch("/api/v1/cases/{caseId}/status", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_user_status_01")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType("application/json")
                        .content("""
                                {
                                  "targetStatus":"IN_REVIEW",
                                  "assigneeRef":"20000000-0000-4000-8000-000000000002",
                                  "reasonCode":"CASE_REVIEW_STARTED",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk());
    }

    private void performAssignee() throws Exception {
        FraudCase fraudCase = reviewCase();
        stubCaseAndVersionFlush(fraudCase);
        mockMvc.perform(patch("/api/v1/cases/{caseId}/assignee", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_user_assignee_01")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType("application/json")
                        .content("""
                                {
                                  "assigneeRef":"30000000-0000-4000-8000-000000000003",
                                  "reasonCode":"CASE_ASSIGNEE_CHANGED",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk());
    }

    private void performResolution() throws Exception {
        FraudCase fraudCase = reviewCase();
        stubCaseAndVersionFlush(fraudCase);
        mockMvc.perform(post("/api/v1/cases/{caseId}/resolution", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_user_resolution_01")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType("application/json")
                        .content("""
                                {
                                  "finalDisposition":"CONFIRMED_FRAUD",
                                  "reasonCode":"CASE_RESOLUTION_COMPLETED",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isOk());
    }

    private void performNote() throws Exception {
        FraudCase fraudCase = reviewCase();
        ReflectionTestUtils.setField(fraudCase, "id", 11L);
        stubCaseAndVersionFlush(fraudCase);
        mockMvc.perform(post("/api/v1/cases/{caseId}/notes", CASE_ID)
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace_user_note_01")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType("application/json")
                        .content("""
                                {
                                  "content":"signed memo",
                                  "expectedVersion":0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("USER"))
                .andExpect(jsonPath("$.authorRef").value(USER_SUBJECT));
    }

    private AuditLogDraft assertSingleUserAudit() {
        var captor = forClass(AuditLogDraft.class);
        verify(auditLogService).append(captor.capture());
        AuditLogDraft audit = captor.getValue();
        assertThat(audit.actorType()).isEqualTo(AuditActorType.USER);
        assertThat(audit.actorId()).isEqualTo(USER_SUBJECT);
        return audit;
    }

    private void stubCaseAndVersionFlush(FraudCase fraudCase) {
        when(fraudCaseRepository.findByCaseId(CASE_ID))
                .thenReturn(Optional.of(fraudCase));
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(
                    fraudCase,
                    "concurrencyVersion",
                    fraudCase.getConcurrencyVersion() + 1
            );
            return null;
        }).when(fraudCaseRepository).flush();
    }

    private FraudCase openCase() {
        return FraudCase.open(
                CASE_ID,
                Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS)
        );
    }

    private FraudCase reviewCase() {
        FraudCase fraudCase = openCase();
        fraudCase.startReview(
                ASSIGNEE,
                Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS)
        );
        return fraudCase;
    }

    private String userToken() {
        return JWT.sign(JWT.validClaims(
                "USER",
                List.of("FDS_ANALYST", "FDS_APPROVER")
        ));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionConfiguration {

        @Bean(name = "transactionManager")
        AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(
                        Object transaction,
                        TransactionDefinition definition
                ) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}
