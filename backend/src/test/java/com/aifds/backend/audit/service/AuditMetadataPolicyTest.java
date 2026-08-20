package com.aifds.backend.audit.service;

import com.aifds.backend.audit.entity.AuditAction;
import com.aifds.backend.audit.entity.AuditActorType;
import com.aifds.backend.audit.entity.AuditLog;
import com.aifds.backend.audit.entity.AuditReasonCode;
import com.aifds.backend.audit.entity.AuditTargetType;
import com.aifds.backend.audit.validation.AuditJsonPayloadGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuditMetadataPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditMetadataPolicy policy = new AuditMetadataPolicy();

    @Test
    void acceptsApprovedPayloadForEveryAction() {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();

        policy.validate(draft(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                null,
                object("caseStatus", "OPEN"),
                detectionMetadata()
        ));
        policy.validate(draft(
                AuditAction.CASE_TRANSACTION_LINKED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                null,
                object("linked", true),
                detectionMetadata()
        ));
        policy.validate(draft(
                AuditAction.TRANSACTION_RISK_RESPONSE_APPLIED,
                AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                null,
                null,
                object("riskResponseOutcome", "HELD"),
                transactionMetadata()
        ));
        policy.validate(draft(
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                null,
                object("processingStatus", "ANALYZED"),
                object("processingStatus", "HELD"),
                transactionMetadata()
        ));
    }

    @Test
    void rejectsUnexpectedMissingAndNestedSummaryValues() {
        UUID caseId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        ObjectNode unexpected = object("caseStatus", "OPEN");
        unexpected.put("customerId", "forbidden");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                draft(
                        AuditAction.CASE_CREATED,
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        AuditTargetType.FRAUD_CASE,
                        caseId,
                        transactionId,
                        caseId,
                        null,
                        unexpected,
                        emptyObject()
                )
        )).withMessageContaining("fields do not match");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                draft(
                        AuditAction.CASE_CREATED,
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        AuditTargetType.FRAUD_CASE,
                        caseId,
                        transactionId,
                        caseId,
                        null,
                        emptyObject(),
                        emptyObject()
                )
        )).withMessageContaining("fields do not match");

        ObjectNode nested = emptyObject();
        nested.replace("caseStatus", emptyObject());
        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                draft(
                        AuditAction.CASE_CREATED,
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        AuditTargetType.FRAUD_CASE,
                        caseId,
                        transactionId,
                        caseId,
                        null,
                        nested,
                        emptyObject()
                )
        )).withMessageContaining("arrays or nested objects");
    }

    @Test
    void rejectsUnsupportedMetadataKeysAndInvalidScalarFormats() {
        UUID caseId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        ObjectNode forbidden = emptyObject();
        forbidden.put("senderAccountRef", "account-raw");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                caseCreatedDraft(caseId, transactionId, forbidden)
        )).withMessageContaining("unsupported fields");

        ObjectNode badUuid = emptyObject();
        badUuid.put("detectionResultId", "not-a-uuid");
        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                caseCreatedDraft(caseId, transactionId, badUuid)
        )).withMessageContaining("UUID v4");

        ObjectNode badVersion = emptyObject();
        badVersion.put("detectionResultVersion", 0);
        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                caseCreatedDraft(caseId, transactionId, badVersion)
        )).withMessageContaining("positive integer");

        ObjectNode nullValue = emptyObject();
        nullValue.putNull("detectionResultId");
        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                caseCreatedDraft(caseId, transactionId, nullValue)
        )).withMessageContaining("non-null scalars");
    }

    @Test
    void rejectsNonObjectAndOversizedJson() {
        UUID caseId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        JsonNode array = objectMapper.createArrayNode().add("OPEN");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                draft(
                        AuditAction.CASE_CREATED,
                        AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                        AuditTargetType.FRAUD_CASE,
                        caseId,
                        transactionId,
                        caseId,
                        null,
                        array,
                        emptyObject()
                )
        )).withMessageContaining("JSON object");

        ObjectNode oversized = emptyObject();
        oversized.put("unsupported", "x".repeat(8_193));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                caseCreatedDraft(caseId, transactionId, oversized)
        )).withMessageContaining("oversized scalar");
    }

    @Test
    void rejectsDeepNestedObjectBeforeFirstDeepCopy() {
        TrackingObjectNode root = new TrackingObjectNode();
        ObjectNode cursor = root;
        for (int depth = 0; depth < 1_000; depth++) {
            ObjectNode nested = emptyObject();
            cursor.replace("nested", nested);
            cursor = nested;
        }

        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                null,
                root,
                emptyObject()
        )).withMessageContaining("arrays or nested objects");
        assertThat(root.deepCopyInvoked()).isFalse();
    }

    @Test
    void rejectsFieldCountAndLargeScalarBeforeFirstDeepCopy() {
        TrackingObjectNode tooManyFields = new TrackingObjectNode();
        for (int index = 0; index < 9; index++) {
            tooManyFields.put("field" + index, index + 1);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                null,
                tooManyFields,
                emptyObject()
        )).withMessageContaining("8 fields");
        assertThat(tooManyFields.deepCopyInvoked()).isFalse();

        TrackingObjectNode oversized = new TrackingObjectNode();
        oversized.put("value", "x".repeat(8_193));
        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                null,
                oversized,
                emptyObject()
        )).withMessageContaining("oversized scalar");
        assertThat(oversized.deepCopyInvoked()).isFalse();
    }

    @Test
    void rejectsArrayRootBeforeFirstDeepCopy() {
        TrackingArrayNode array = new TrackingArrayNode();
        array.add("OPEN");

        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                null,
                array,
                emptyObject()
        )).withMessageContaining("JSON object");
        assertThat(array.deepCopyInvoked()).isFalse();
    }

    @Test
    void calculatesPostgresqlJsonbTextBytesFromIndependentLiterals() {
        ObjectNode empty = emptyObject();
        ObjectNode oneEntry = object("a", "B");
        ObjectNode multipleEntries = emptyObject();
        multipleEntries.put("a", "B");
        multipleEntries.put("linked", true);

        assertThat(bytes(empty)).isEqualTo(
                "{}".getBytes(StandardCharsets.UTF_8).length
        );
        assertThat(bytes(oneEntry)).isEqualTo(
                "{\"a\": \"B\"}".getBytes(StandardCharsets.UTF_8).length
        );
        assertThat(bytes(multipleEntries)).isEqualTo(
                "{\"a\": \"B\", \"linked\": true}"
                        .getBytes(StandardCharsets.UTF_8).length
        );
    }

    @Test
    void allows8192BytesAndRejects8193Bytes() {
        ObjectNode exactly8192 = object("v", "x".repeat(8_183));
        ObjectNode exactly8193 = object("v", "x".repeat(8_184));

        AuditLogDraft accepted = structuralDraft(
                null,
                null,
                exactly8192
        );

        assertThat(bytes(accepted.metadata())).isEqualTo(8_192);
        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                null,
                null,
                exactly8193
        )).withMessageContaining("8192 bytes");
    }

    @Test
    void applies8192ByteBoundaryToSumOfThreeJsonObjects() {
        ObjectNode before = object("v", "x".repeat(1_000));
        ObjectNode after = object("v", "x".repeat(2_000));
        ObjectNode metadataAtBoundary = object(
                "v",
                "x".repeat(5_165)
        );
        ObjectNode metadataOverBoundary = object(
                "v",
                "x".repeat(5_166)
        );

        AuditLogDraft accepted = structuralDraft(
                before,
                after,
                metadataAtBoundary
        );

        assertThat(bytes(accepted.beforeValueSummary())
                + bytes(accepted.afterValueSummary())
                + bytes(accepted.metadata())).isEqualTo(8_192);
        assertThatIllegalArgumentException().isThrownBy(() -> structuralDraft(
                before,
                after,
                metadataOverBoundary
        )).withMessageContaining("8192 bytes");
    }

    @ParameterizedTest
    @EnumSource(AuditAction.class)
    void rejectsUnexpectedKeyForEveryAction(AuditAction action) {
        AuditLogDraft valid = validDraft(action);
        ObjectNode after = (ObjectNode) valid.afterValueSummary();
        after.put("customerId", "forbidden");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                copyWith(valid, valid.beforeValueSummary(), after,
                        valid.metadata())
        )).withMessageContaining("fields do not match");
    }

    @ParameterizedTest
    @EnumSource(AuditAction.class)
    void rejectsInvalidValueForEveryAction(AuditAction action) {
        AuditLogDraft valid = validDraft(action);

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                copyWith(
                        valid,
                        valid.beforeValueSummary(),
                        invalidAfter(action),
                        valid.metadata()
                )
        ));
    }

    @ParameterizedTest
    @EnumSource(AuditAction.class)
    void rejectsNullNestedAndArrayValuesForEveryAction(AuditAction action) {
        AuditLogDraft valid = validDraft(action);
        ObjectNode nullValue = validAfter(action);
        nullValue.putNull(nullValue.fieldNames().next());
        ObjectNode nestedValue = validAfter(action);
        nestedValue.replace(
                nestedValue.fieldNames().next(),
                emptyObject()
        );
        JsonNode array = objectMapper.createArrayNode().add("invalid");

        assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(
                copyWith(
                        valid,
                        valid.beforeValueSummary(),
                        null,
                        valid.metadata()
                )
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> copyWith(
                valid,
                valid.beforeValueSummary(),
                nullValue,
                valid.metadata()
        )).withMessageContaining("non-null scalars");
        assertThatIllegalArgumentException().isThrownBy(() -> copyWith(
                valid,
                valid.beforeValueSummary(),
                nestedValue,
                valid.metadata()
        )).withMessageContaining("arrays or nested objects");
        assertThatIllegalArgumentException().isThrownBy(() -> copyWith(
                valid,
                valid.beforeValueSummary(),
                array,
                valid.metadata()
        )).withMessageContaining("JSON object");
    }

    @Test
    void draftDefensivelyCopiesJsonOnInputAndAccess() {
        UUID caseId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        ObjectNode after = object("caseStatus", "OPEN");
        ObjectNode metadata = detectionMetadata();
        AuditLogDraft draft = draft(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                null,
                after,
                metadata
        );

        after.put("caseStatus", "CLOSED");
        metadata.put("detectionResultVersion", 99);
        ObjectNode exposed = (ObjectNode) draft.metadata();
        exposed.put("detectionResultVersion", 100);

        assertThat(draft.afterValueSummary().get("caseStatus").textValue())
                .isEqualTo("OPEN");
        assertThat(draft.metadata().get("detectionResultVersion").intValue())
                .isEqualTo(1);
    }

    private AuditLogDraft caseCreatedDraft(
            UUID caseId,
            UUID transactionId,
            JsonNode metadata
    ) {
        return draft(
                AuditAction.CASE_CREATED,
                AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                AuditTargetType.FRAUD_CASE,
                caseId,
                transactionId,
                caseId,
                null,
                object("caseStatus", "OPEN"),
                metadata
        );
    }

    private AuditLogDraft validDraft(AuditAction action) {
        UUID transactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        return switch (action) {
            case CASE_CREATED -> draft(
                    action,
                    AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                    AuditTargetType.FRAUD_CASE,
                    caseId,
                    transactionId,
                    caseId,
                    null,
                    object("caseStatus", "OPEN"),
                    detectionMetadata()
            );
            case CASE_TRANSACTION_LINKED -> draft(
                    action,
                    AuditReasonCode.CASE_REQUIRED_BY_RISK_POLICY,
                    AuditTargetType.FRAUD_CASE,
                    caseId,
                    transactionId,
                    caseId,
                    null,
                    object("linked", true),
                    detectionMetadata()
            );
            case TRANSACTION_RISK_RESPONSE_APPLIED -> draft(
                    action,
                    AuditReasonCode.RISK_RESPONSE_DECIDED_BY_POLICY,
                    AuditTargetType.FINANCIAL_TRANSACTION,
                    transactionId,
                    transactionId,
                    null,
                    object("riskResponseOutcome", "APPROVED"),
                    object("riskResponseOutcome", "HELD"),
                    transactionMetadata()
            );
            case TRANSACTION_STATUS_CHANGED -> draft(
                    action,
                    AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                    AuditTargetType.FINANCIAL_TRANSACTION,
                    transactionId,
                    transactionId,
                    null,
                    object("processingStatus", "ANALYZED"),
                    object("processingStatus", "HELD"),
                    transactionMetadata()
            );
        };
    }

    private AuditLogDraft copyWith(
            AuditLogDraft source,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        return new AuditLogDraft(
                source.actorType(),
                source.actorId(),
                source.action(),
                source.reasonCode(),
                source.targetType(),
                source.targetId(),
                source.transactionId(),
                source.caseId(),
                source.traceId(),
                before,
                after,
                metadata
        );
    }

    private ObjectNode validAfter(AuditAction action) {
        return switch (action) {
            case CASE_CREATED -> object("caseStatus", "OPEN");
            case CASE_TRANSACTION_LINKED -> object("linked", true);
            case TRANSACTION_RISK_RESPONSE_APPLIED ->
                    object("riskResponseOutcome", "HELD");
            case TRANSACTION_STATUS_CHANGED ->
                    object("processingStatus", "HELD");
        };
    }

    private ObjectNode invalidAfter(AuditAction action) {
        return switch (action) {
            case CASE_CREATED -> object("caseStatus", "CLOSED");
            case CASE_TRANSACTION_LINKED -> object("linked", false);
            case TRANSACTION_RISK_RESPONSE_APPLIED ->
                    object("riskResponseOutcome", "UNKNOWN");
            case TRANSACTION_STATUS_CHANGED ->
                    object("processingStatus", "UNKNOWN");
        };
    }

    private AuditLogDraft structuralDraft(
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        UUID transactionId = UUID.randomUUID();
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                "finguardops-backend",
                AuditAction.TRANSACTION_STATUS_CHANGED,
                AuditReasonCode.TRANSACTION_FINALIZED_BY_RISK_POLICY,
                AuditTargetType.FINANCIAL_TRANSACTION,
                transactionId,
                transactionId,
                null,
                null,
                before,
                after,
                metadata
        );
    }

    private int bytes(JsonNode value) {
        return AuditJsonPayloadGuard.postgresqlJsonbTextUtf8Bytes(
                value,
                "testValue",
                false
        );
    }

    private AuditLogDraft draft(
            AuditAction action,
            AuditReasonCode reasonCode,
            AuditTargetType targetType,
            UUID targetId,
            UUID transactionId,
            UUID caseId,
            JsonNode before,
            JsonNode after,
            JsonNode metadata
    ) {
        return new AuditLogDraft(
                AuditActorType.SYSTEM,
                AuditLog.SYSTEM_ACTOR_ID,
                action,
                reasonCode,
                targetType,
                targetId,
                transactionId,
                caseId,
                "trace_audit_test_01",
                before,
                after,
                metadata
        );
    }

    private ObjectNode detectionMetadata() {
        ObjectNode metadata = emptyObject();
        metadata.put("detectionResultId", UUID.randomUUID().toString());
        metadata.put("detectionResultVersion", 1);
        return metadata;
    }

    private ObjectNode transactionMetadata() {
        ObjectNode metadata = detectionMetadata();
        metadata.put("sourceRiskLevel", "CRITICAL");
        return metadata;
    }

    private ObjectNode emptyObject() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode object(String field, String value) {
        return emptyObject().put(field, value);
    }

    private ObjectNode object(String field, boolean value) {
        return emptyObject().put(field, value);
    }

    private static final class TrackingObjectNode extends ObjectNode {

        private boolean deepCopyInvoked;

        private TrackingObjectNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public ObjectNode deepCopy() {
            deepCopyInvoked = true;
            throw new AssertionError("deepCopy must not run before validation");
        }

        private boolean deepCopyInvoked() {
            return deepCopyInvoked;
        }
    }

    private static final class TrackingArrayNode extends ArrayNode {

        private boolean deepCopyInvoked;

        private TrackingArrayNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public ArrayNode deepCopy() {
            deepCopyInvoked = true;
            throw new AssertionError("deepCopy must not run before validation");
        }

        private boolean deepCopyInvoked() {
            return deepCopyInvoked;
        }
    }
}
