package com.aifds.backend.rule.client;

import com.aifds.backend.rule.client.config.RuleAnalysisClientConfiguration;
import com.aifds.backend.rule.client.dto.RuleAnalysisRequestV2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class RuleAnalysisRequestV2DtoTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RuleAnalysisClientConfiguration().ruleAnalysisObjectMapper(
                new Jackson2ObjectMapperBuilder()
        );
    }

    @Test
    void serializesTheExactPythonV2RequestFixture() throws Exception {
        RuleAnalysisRequestV2 request =
                RuleAnalysisClientTestFixtures.pythonV2Request(mapper);

        JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(request));
        JsonNode exactPythonFixture = mapper.readTree("""
                {
                  "evaluationCutoffAt": "2026-07-23T12:00:00Z",
                  "transaction": {
                    "transactionId": "10000000-0000-4000-8000-000000000001",
                    "transactionType": "ACCOUNT_TRANSFER",
                    "amount": "12000000",
                    "currencyCode": "KRW",
                    "occurredAt": "2026-07-23T12:00:00Z",
                    "externalCustomerRef": "customer-a",
                    "senderAccountRef": "sender-a",
                    "recipientAccountRef": "recipient-a",
                    "deviceRef": "device-a"
                  },
                  "behaviorEvents": [
                    {
                      "eventId": "30000000-0000-4000-8000-000000000001",
                      "eventType": "DEVICE_REGISTERED",
                      "occurredAt": "2026-07-23T11:55:00Z",
                      "externalCustomerRef": "customer-a",
                      "accountRef": null,
                      "deviceRef": "device-a",
                      "beneficiaryRef": null
                    },
                    {
                      "eventId": "30000000-0000-4000-8000-000000000002",
                      "eventType": "PASSWORD_CHANGED",
                      "occurredAt": "2026-07-23T11:56:00.123456Z",
                      "externalCustomerRef": "customer-a",
                      "accountRef": null,
                      "deviceRef": null,
                      "beneficiaryRef": null
                    },
                    {
                      "eventId": "30000000-0000-4000-8000-000000000003",
                      "eventType": "TRANSFER_LIMIT_CHANGED",
                      "occurredAt": "2026-07-23T11:57:00Z",
                      "externalCustomerRef": "customer-a",
                      "accountRef": "sender-a",
                      "deviceRef": null,
                      "beneficiaryRef": null
                    },
                    {
                      "eventId": "30000000-0000-4000-8000-000000000004",
                      "eventType": "BENEFICIARY_REGISTERED",
                      "occurredAt": "2026-07-23T11:59:00Z",
                      "externalCustomerRef": "customer-a",
                      "accountRef": "sender-a",
                      "deviceRef": null,
                      "beneficiaryRef": "recipient-a"
                    }
                  ],
                  "ruleVersions": [
                    {
                      "fraudRuleId": "10000000-0000-4000-8000-000000000011",
                      "ruleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                      "lifecycleStatus": "ACTIVE",
                      "ruleVersionId": "20000000-0000-4000-8000-000000000001",
                      "versionNumber": 1,
                      "status": "PUBLISHED",
                      "reasonCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                      "weight": 15,
                      "conditionDefinition": {
                        "transactionTypes": [
                          "ACCOUNT_TRANSFER",
                          "OPEN_BANKING_TRANSFER"
                        ],
                        "currencyCode": "KRW",
                        "amountThreshold": "10000000"
                      },
                      "effectiveFrom": "2026-07-22T12:00:00Z",
                      "effectiveTo": null
                    },
                    {
                      "fraudRuleId": "10000000-0000-4000-8000-000000000012",
                      "ruleCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
                      "lifecycleStatus": "ACTIVE",
                      "ruleVersionId": "20000000-0000-4000-8000-000000000002",
                      "versionNumber": 2,
                      "status": "PUBLISHED",
                      "reasonCode": "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
                      "weight": 20,
                      "conditionDefinition": {
                        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                        "eventType": "DEVICE_REGISTERED",
                        "windowSeconds": 86400,
                        "matchPolicy": "SAME_CUSTOMER_AND_DEVICE",
                        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                      },
                      "effectiveFrom": "2026-07-22T12:00:00Z",
                      "effectiveTo": null
                    },
                    {
                      "fraudRuleId": "10000000-0000-4000-8000-000000000013",
                      "ruleCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
                      "lifecycleStatus": "ACTIVE",
                      "ruleVersionId": "20000000-0000-4000-8000-000000000003",
                      "versionNumber": 3,
                      "status": "PUBLISHED",
                      "reasonCode": "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
                      "weight": 40,
                      "conditionDefinition": {
                        "prerequisiteRuleCode": "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
                        "passwordEventType": "PASSWORD_CHANGED",
                        "transferLimitEventType": "TRANSFER_LIMIT_CHANGED",
                        "windowSeconds": 86400,
                        "matchPolicy": "SAME_CUSTOMER_AND_SENDER_ACCOUNT",
                        "sequencePolicy": "PASSWORD_CHANGED_AT_OR_BEFORE_TRANSFER_LIMIT_CHANGED",
                        "selectionPolicy": "LATEST_TRANSFER_LIMIT_THEN_EVENT_ID_ASC_LATEST_PASSWORD_THEN_EVENT_ID_ASC"
                      },
                      "effectiveFrom": "2026-07-22T12:00:00Z",
                      "effectiveTo": null
                    },
                    {
                      "fraudRuleId": "10000000-0000-4000-8000-000000000014",
                      "ruleCode": "RECENT_BENEFICIARY_TRANSFER",
                      "lifecycleStatus": "ACTIVE",
                      "ruleVersionId": "20000000-0000-4000-8000-000000000004",
                      "versionNumber": 4,
                      "status": "PUBLISHED",
                      "reasonCode": "RECENT_BENEFICIARY_TRANSFER",
                      "weight": 10,
                      "conditionDefinition": {
                        "eventType": "BENEFICIARY_REGISTERED",
                        "windowSeconds": 86400,
                        "matchPolicy": "SAME_CUSTOMER_SENDER_ACCOUNT_AND_BENEFICIARY",
                        "selectionPolicy": "LATEST_OCCURRED_AT_THEN_EVENT_ID_ASC"
                      },
                      "effectiveFrom": "2026-07-22T12:00:00Z",
                      "effectiveTo": null
                    }
                  ],
                  "externalRisk": {
                    "providerCode": "EXTERNAL_RISK_MOCK_V1",
                    "lookupStatus": "SUCCEEDED",
                    "policyResult": "MATCHED",
                    "providerAsOf": "2026-07-23T11:59:59.123456Z",
                    "lookedUpAt": "2026-07-23T12:00:00.654321Z",
                    "matches": [
                      {
                        "subjectType": "SENDER_ACCOUNT",
                        "externalRiskType": "SUSPICIOUS_ACCOUNT",
                        "reasonCode": "SUSPICIOUS_SENDER_ACCOUNT"
                      }
                    ]
                  }
                }
                """);

        assertThat(actual).isEqualTo(exactPythonFixture);
        JsonNode externalRisk = actual.path("externalRisk");
        assertThat(externalRisk.fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "providerCode",
                        "lookupStatus",
                        "policyResult",
                        "providerAsOf",
                        "lookedUpAt",
                        "matches"
                );
        assertThat(externalRisk.has("policyResult")).isTrue();
        assertThat(externalRisk.has("result")).isFalse();
        assertThat(externalRisk.has("transactionId")).isFalse();
        assertThat(externalRisk.has("evaluationCutoffAt")).isFalse();
        assertThat(externalRisk.has("traceId")).isFalse();
        assertThat(externalRisk.toString())
                .doesNotContain(
                        "customer-a",
                        "sender-a",
                        "recipient-a",
                        "device-a"
                );
    }

    @Test
    void preservesTheV1SnapshotListsAsImmutableCopies() {
        RuleAnalysisRequestV2 request =
                RuleAnalysisClientTestFixtures.requestV2(mapper);

        assertThat(request.behaviorEvents()).isUnmodifiable();
        assertThat(request.ruleVersions()).isUnmodifiable();
        assertThat(request.externalRisk().matches()).isUnmodifiable();
    }
}
