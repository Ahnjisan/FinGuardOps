package com.aifds.backend.behavior.fingerprint;

import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.validation.BehaviorEventRequestValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorEventRequestFingerprintTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "e54cbf7e-d857-4ca0-bff3-8d4321b7722a"
    );
    private static final UUID TRANSACTION_ID = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-07-29T04:10:00Z");

    private final BehaviorEventRequestFingerprint fingerprint =
            new BehaviorEventRequestFingerprint(new ObjectMapper());

    @Test
    void normalizesExactlyEightFieldsInApprovedOrderWithExplicitNulls() {
        String normalized = fingerprint.normalize(input(
                null,
                null,
                null,
                null
        ));

        assertThat(normalized).isEqualTo(
                "{\"eventId\":\"e54cbf7e-d857-4ca0-bff3-8d4321b7722a\","
                        + "\"eventType\":\"LOGIN_FAILED\","
                        + "\"occurredAt\":\"2026-07-29T04:10:00Z\","
                        + "\"externalCustomerRef\":\"customer\","
                        + "\"accountRef\":null,"
                        + "\"deviceRef\":null,"
                        + "\"transactionId\":null,"
                        + "\"beneficiaryRef\":null}"
        );
    }

    @Test
    void calculatesDeterministicLowercaseSha256() {
        BehaviorEventFingerprintInput input = input(
                "account",
                "device",
                TRANSACTION_ID,
                null
        );

        String first = fingerprint.calculate(input);
        String second = fingerprint.calculate(input);

        assertThat(first)
                .isEqualTo(second)
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void separatesFieldBoundariesAndNullFromText() {
        String joinedInAccount = fingerprint.calculate(input(
                "account|device",
                null,
                null,
                null
        ));
        String splitAcrossFields = fingerprint.calculate(input(
                "account",
                "device",
                null,
                null
        ));
        String textNull = fingerprint.calculate(input(
                "null",
                null,
                null,
                null
        ));
        String actualNull = fingerprint.calculate(input(
                null,
                null,
                null,
                null
        ));

        assertThat(joinedInAccount).isNotEqualTo(splitAcrossFields);
        assertThat(textNull).isNotEqualTo(actualNull);
    }

    @Test
    void changesWhenAnyIncludedFieldChanges() {
        BehaviorEventFingerprintInput base = input(
                "account",
                "device",
                TRANSACTION_ID,
                "beneficiary"
        );
        String baseHash = fingerprint.calculate(base);

        assertThat(fingerprint.calculate(new BehaviorEventFingerprintInput(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                base.eventType(),
                base.occurredAt(),
                base.externalCustomerRef(),
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        ))).isNotEqualTo(baseHash);
        assertThat(fingerprint.calculate(new BehaviorEventFingerprintInput(
                base.eventId(),
                BehaviorEventType.LOGIN,
                base.occurredAt(),
                base.externalCustomerRef(),
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        ))).isNotEqualTo(baseHash);
        assertThat(fingerprint.calculate(new BehaviorEventFingerprintInput(
                base.eventId(),
                base.eventType(),
                base.occurredAt().plusSeconds(1),
                base.externalCustomerRef(),
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        ))).isNotEqualTo(baseHash);
        assertThat(fingerprint.calculate(new BehaviorEventFingerprintInput(
                base.eventId(),
                base.eventType(),
                base.occurredAt(),
                "other",
                base.accountRef(),
                base.deviceRef(),
                base.transactionId(),
                base.beneficiaryRef()
        ))).isNotEqualTo(baseHash);
    }

    @Test
    void omittedAndExplicitNullOptionalFieldsHaveSameFingerprint()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        BehaviorEventCreateRequest omitted = objectMapper.readValue("""
                {
                  "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
                  "eventType": "LOGIN_FAILED",
                  "occurredAt": "2026-07-29T04:10:00Z",
                  "externalCustomerRef": "customer"
                }
                """, BehaviorEventCreateRequest.class);
        BehaviorEventCreateRequest explicitNull = objectMapper.readValue("""
                {
                  "eventId": "e54cbf7e-d857-4ca0-bff3-8d4321b7722a",
                  "eventType": "LOGIN_FAILED",
                  "occurredAt": "2026-07-29T04:10:00Z",
                  "externalCustomerRef": "customer",
                  "accountRef": null,
                  "deviceRef": null,
                  "transactionId": null,
                  "beneficiaryRef": null
                }
                """, BehaviorEventCreateRequest.class);
        BehaviorEventRequestValidator validator =
                new BehaviorEventRequestValidator(
                        Clock.fixed(OCCURRED_AT, ZoneOffset.UTC),
                        Validation.buildDefaultValidatorFactory().getValidator()
                );

        assertThat(fingerprint.calculate(
                validator.validate(omitted).toFingerprintInput()
        )).isEqualTo(fingerprint.calculate(
                validator.validate(explicitNull).toFingerprintInput()
        ));
    }

    private BehaviorEventFingerprintInput input(
            String accountRef,
            String deviceRef,
            UUID transactionId,
            String beneficiaryRef
    ) {
        return new BehaviorEventFingerprintInput(
                EVENT_ID,
                BehaviorEventType.LOGIN_FAILED,
                OCCURRED_AT,
                "customer",
                accountRef,
                deviceRef,
                transactionId,
                beneficiaryRef
        );
    }
}
