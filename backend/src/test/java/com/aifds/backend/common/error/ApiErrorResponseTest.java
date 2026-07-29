package com.aifds.backend.common.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesExactlyTheContractFieldsWithNullTraceIdAndEmptyFieldErrors()
            throws Exception {
        ApiErrorResponse response = new ApiErrorResponse(
                "INTERNAL_ERROR",
                "요청을 처리하는 중 오류가 발생했습니다.",
                null,
                null
        );

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(response)
        );

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "code",
                "message",
                "traceId",
                "fieldErrors"
        );
        assertThat(json.get("traceId").isNull()).isTrue();
        assertThat(json.get("fieldErrors").isArray()).isTrue();
        assertThat(json.get("fieldErrors").isEmpty()).isTrue();
    }

    @Test
    void makesAnImmutableSortedDistinctCopyOfFieldErrors() {
        FieldErrorResponse beta = new FieldErrorResponse(
                "beta",
                "REQUIRED_FIELD",
                "beta is required"
        );
        FieldErrorResponse alpha = new FieldErrorResponse(
                "alpha",
                "REQUIRED_FIELD",
                "alpha is required"
        );
        List<FieldErrorResponse> source = new ArrayList<>(
                List.of(beta, alpha, alpha)
        );

        ApiErrorResponse response = new ApiErrorResponse(
                "VALIDATION_ERROR",
                "요청 필드를 확인해 주세요.",
                null,
                source
        );
        source.clear();

        assertThat(response.fieldErrors()).containsExactly(alpha, beta);
        assertThatThrownBy(() -> response.fieldErrors().add(beta))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullFieldErrorElementsAndNullFieldErrorProperties() {
        List<FieldErrorResponse> fieldErrors = new ArrayList<>();
        fieldErrors.add(null);

        assertThatThrownBy(() -> new ApiErrorResponse(
                "VALIDATION_ERROR",
                "요청 필드를 확인해 주세요.",
                null,
                fieldErrors
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldErrorResponse(
                null,
                "REQUIRED_FIELD",
                "field is required"
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldErrorResponse(
                "field",
                null,
                "field is required"
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldErrorResponse(
                "field",
                "REQUIRED_FIELD",
                null
        )).isInstanceOf(NullPointerException.class);
    }

    private Set<String> fieldNames(JsonNode json) {
        Iterator<String> names = json.fieldNames();
        Iterable<String> iterable = () -> names;
        return StreamSupport.stream(iterable.spliterator(), false)
                .collect(Collectors.toSet());
    }
}
