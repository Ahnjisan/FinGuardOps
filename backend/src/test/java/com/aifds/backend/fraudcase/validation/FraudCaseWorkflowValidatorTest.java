package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseAssigneeChangeRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseStatusChangeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseWorkflowValidatorTest {

    private static final String CASE_ID =
            "1a000000-0000-4000-9000-000000000001";
    private static final String ASSIGNEE =
            "2a000000-0000-4000-9000-000000000002";

    private final FraudCaseWorkflowValidator validator =
            new FraudCaseWorkflowValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesStatusAndPreservesAssigneePresenceWithoutNormalization() {
        FraudCaseWorkflowCommand.StatusChange command =
                validator.validateStatus(
                        CASE_ID,
                        new FraudCaseStatusChangeRequest(
                                "IN_REVIEW",
                                ASSIGNEE,
                                true,
                                "CASE_REVIEW_STARTED",
                                0L
                        )
                );

        assertThat(command.caseId().toString()).isEqualTo(CASE_ID);
        assertThat(command.assigneeRef()).isEqualTo(ASSIGNEE);
        assertThat(command.assigneeRefPresent()).isTrue();
        assertThat(command.expectedVersion()).isZero();
    }

    @Test
    void distinguishesMissingAssigneeCommandFromExplicitNull() {
        assertThatThrownBy(() -> validator.validateAssignee(
                CASE_ID,
                new FraudCaseAssigneeChangeRequest(
                        null,
                        false,
                        "CASE_ASSIGNEE_RELEASED",
                        3L
                )
        )).isInstanceOf(FraudCaseValidationException.class)
                .extracting(exception ->
                        ((FraudCaseValidationException) exception).getCode()
                ).isEqualTo("ASSIGNEE_COMMAND_REQUIRED");

        FraudCaseWorkflowCommand.AssigneeChange release =
                validator.validateAssignee(
                        CASE_ID,
                        new FraudCaseAssigneeChangeRequest(
                                null,
                                true,
                                "CASE_ASSIGNEE_RELEASED",
                                3L
                        )
                );
        assertThat(release.assigneeRef()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "20000000-0000-4000-9000-000000000002 ",
            " 20000000-0000-4000-9000-000000000002",
            "20000000-0000-4000-9000-000000000002\n",
            "20000000-0000-1000-9000-000000000002",
            "20000000-0000-4000-7000-000000000002",
            "٢٠٠٠٠٠٠٠-٠٠٠٠-٤٠٠٠-٩٠٠٠-٠٠٠٠٠٠٠٠٠٠٠٢",
            "analyst@example.com",
            "employee-1234",
            "010-1234-5678",
            "credential-secret"
    })
    void rejectsInvalidAssigneeRefsWithoutReflectingInput(String invalid) {
        assertThatThrownBy(() -> validator.validateAssignee(
                CASE_ID,
                new FraudCaseAssigneeChangeRequest(
                        invalid,
                        true,
                        "CASE_ASSIGNEE_CHANGED",
                        1L
                )
        )).isInstanceOf(FraudCaseValidationException.class)
                .hasMessageNotContaining(invalid);
    }

    @Test
    void rejectsUppercaseAssigneeAndCaseId() {
        assertThatThrownBy(() -> validator.validateAssignee(
                CASE_ID,
                new FraudCaseAssigneeChangeRequest(
                        ASSIGNEE.toUpperCase(),
                        true,
                        "CASE_ASSIGNEE_CHANGED",
                        1L
                )
        )).isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> validator.validateAssignee(
                CASE_ID.toUpperCase(),
                new FraudCaseAssigneeChangeRequest(
                        ASSIGNEE,
                        true,
                        "CASE_ASSIGNEE_CHANGED",
                        1L
                )
        )).isInstanceOf(FraudCaseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1a000000-0000-1000-9000-000000000001",
            "1a000000-0000-4000-7000-000000000001",
            " 1a000000-0000-4000-9000-000000000001",
            "1a000000-0000-4000-9000-000000000001 ",
            "１a000000-0000-4000-9000-000000000001",
            "사000000-0000-4000-9000-000000000001",
            "1a000000-0000-4000-9000-000000000001\n",
            "1a000000-0000-4000-9000-000000000001\001"
    })
    void rejectsUnsafeCaseIdsWithoutReflectingInput(String invalidCaseId) {
        assertThatThrownBy(() -> validator.validateAssignee(
                invalidCaseId,
                new FraudCaseAssigneeChangeRequest(
                        ASSIGNEE,
                        true,
                        "CASE_ASSIGNEE_CHANGED",
                        1L
                )
        )).isInstanceOf(FraudCaseValidationException.class)
                .hasMessageNotContaining(invalidCaseId);
    }

    @Test
    void rejectsMissingFieldsNegativeVersionAndNonWorkflowReason() {
        assertThatThrownBy(() -> validator.validateStatus(
                CASE_ID,
                new FraudCaseStatusChangeRequest(
                        null, null, false, "CASE_REVIEW_STARTED", 0L
                )
        )).isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> validator.validateStatus(
                CASE_ID,
                new FraudCaseStatusChangeRequest(
                        "IN_REVIEW", ASSIGNEE, true,
                        "CASE_REVIEW_STARTED", -1L
                )
        )).isInstanceOf(FraudCaseValidationException.class);
        assertThatThrownBy(() -> validator.validateStatus(
                CASE_ID,
                new FraudCaseStatusChangeRequest(
                        "IN_REVIEW", ASSIGNEE, true,
                        "CASE_REQUIRED_BY_RISK_POLICY", 0L
                )
        )).isInstanceOf(FraudCaseValidationException.class);
    }

    @Test
    void rejectsReasonAndAssigneeSemanticCombinationsAsDomainErrors() {
        assertThatThrownBy(() -> validator.requireReason(
                AuditReasonCode.CASE_REVIEW_STARTED,
                AuditReasonCode.CASE_REVIEW_RESUMED
        )).isInstanceOf(FraudCaseValidationException.class)
                .extracting(exception ->
                        ((FraudCaseValidationException) exception).getType()
                ).isEqualTo(FraudCaseValidationType.DOMAIN);
        assertThatThrownBy(() ->
                validator.rejectStatusAssigneeCombination(true)
        ).isInstanceOf(FraudCaseValidationException.class)
                .extracting(exception ->
                        ((FraudCaseValidationException) exception).getType()
                ).isEqualTo(FraudCaseValidationType.DOMAIN);
    }

    @Test
    void rejectsStatusAndAssigneeReasonCodesUsedAcrossCommandMeanings() {
        for (AuditReasonCode[] mismatch : new AuditReasonCode[][]{
                {
                        AuditReasonCode.CASE_REVIEW_STARTED,
                        AuditReasonCode.CASE_ASSIGNEE_CHANGED
                },
                {
                        AuditReasonCode.CASE_ASSIGNEE_ASSIGNED,
                        AuditReasonCode.CASE_REVIEW_STARTED
                },
                {
                        AuditReasonCode.CASE_REVIEW_RESUMED,
                        AuditReasonCode
                                .CASE_ADDITIONAL_INFORMATION_REQUESTED
                }
        }) {
            assertThatThrownBy(() -> validator.requireReason(
                    mismatch[0],
                    mismatch[1]
            )).isInstanceOf(FraudCaseValidationException.class)
                    .extracting(exception ->
                            ((FraudCaseValidationException) exception)
                                    .getType()
                    ).isEqualTo(FraudCaseValidationType.DOMAIN);
        }
    }

    @Test
    void strictDeserializersRejectUnknownDuplicateWrongTypeAndRoot()
            throws Exception {
        for (String json : new String[]{
                "{\"targetStatus\":\"IN_REVIEW\",\"unknown\":1}",
                "{\"targetStatus\":\"IN_REVIEW\","
                        + "\"targetStatus\":\"OPEN\"}",
                "{\"targetStatus\":1}",
                "{\"targetStatus\":\"IN_REVIEW\"} {}",
                "[]",
                "null"
        }) {
            assertThatThrownBy(() -> objectMapper.readValue(
                    json,
                    FraudCaseStatusChangeRequest.class
            )).hasRootCauseInstanceOf(FraudCaseValidationException.class);
        }
    }

    @Test
    void strictDeserializerKeepsExplicitNullAndRejectsOversizedVersion()
            throws Exception {
        FraudCaseAssigneeChangeRequest request = objectMapper.readValue(
                "{\"assigneeRef\":null,"
                        + "\"reasonCode\":\"CASE_ASSIGNEE_RELEASED\","
                        + "\"expectedVersion\":2}",
                FraudCaseAssigneeChangeRequest.class
        );
        assertThat(request.assigneeRefPresent()).isTrue();
        assertThat(request.assigneeRef()).isNull();

        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"assigneeRef\":null,"
                        + "\"reasonCode\":\"CASE_ASSIGNEE_RELEASED\","
                        + "\"expectedVersion\":9223372036854775808}",
                FraudCaseAssigneeChangeRequest.class
        )).hasRootCauseInstanceOf(FraudCaseValidationException.class);
    }

    @Test
    void rejectsEveryInvalidExpectedVersionJsonForm() throws Exception {
        for (String expectedVersion : new String[]{
                "1.5",
                "\"1\"",
                "true",
                "9223372036854775808"
        }) {
            String json = "{\"targetStatus\":\"IN_REVIEW\","
                    + "\"reasonCode\":\"CASE_REVIEW_STARTED\","
                    + "\"expectedVersion\":" + expectedVersion + "}";
            assertThatThrownBy(() -> objectMapper.readValue(
                    json,
                    FraudCaseStatusChangeRequest.class
            )).hasRootCauseInstanceOf(FraudCaseValidationException.class);
        }

        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"targetStatus\":\"IN_REVIEW\","
                        + "\"reasonCode\":\"CASE_REVIEW_STARTED\","
                        + "\"expectedVersion\":null}",
                FraudCaseStatusChangeRequest.class
        )).hasRootCauseInstanceOf(FraudCaseValidationException.class);

        FraudCaseStatusChangeRequest missing = objectMapper.readValue(
                "{\"targetStatus\":\"IN_REVIEW\","
                        + "\"reasonCode\":\"CASE_REVIEW_STARTED\"}",
                FraudCaseStatusChangeRequest.class
        );
        assertThatThrownBy(() -> validator.validateStatus(CASE_ID, missing))
                .isInstanceOf(FraudCaseValidationException.class)
                .extracting(exception ->
                        ((FraudCaseValidationException) exception).getCode()
                ).isEqualTo(FraudCaseWorkflowValidator.REQUIRED_FIELD);
    }

    @Test
    void assigneeDeserializerRejectsUnknownDuplicateTrailingAndWrongTypes() {
        for (String json : new String[]{
                "{\"assigneeRef\":null,\"unknown\":true}",
                "{\"assigneeRef\":null,\"assigneeRef\":\""
                        + ASSIGNEE + "\"}",
                "{\"assigneeRef\":null} {}",
                "{\"assigneeRef\":123}",
                "{\"assigneeRef\":null,\"expectedVersion\":false}",
                "{\"assigneeRef\":null,\"expectedVersion\":\"1\"}"
        }) {
            assertThatThrownBy(() -> objectMapper.readValue(
                    json,
                    FraudCaseAssigneeChangeRequest.class
            )).hasRootCauseInstanceOf(FraudCaseValidationException.class);
        }
    }
}
