package com.aifds.backend.fraudcase.validation;

import com.aifds.backend.fraudcase.command.FraudCaseNoteCommand;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationNoteValidatorTest {

    private static final String CASE_ID = "1a000000-0000-4000-8000-000000000001";
    private final InvestigationNoteValidator validator = new InvestigationNoteValidator();

    @Test
    void preservesUnicodeAndCountsCodePointsInsteadOfUtf16Units() {
        String content = "  😀조사\r\n메모  ";
        FraudCaseNoteCommand.Create command = validator.validateCreate(
                CASE_ID, new InvestigationNoteCreateRequest(content, 6L)
        );
        validator.validateContent(command.content());
        validator.validateContent("😀".repeat(4_000));

        assertThat(command.content()).isSameAs(content);
        assertThatThrownBy(() -> validator.validateContent("😀".repeat(4_001)))
                .isInstanceOf(InvestigationNoteValidationException.class)
                .extracting("type").isEqualTo(InvestigationNoteValidationType.DOMAIN);
    }

    @Test
    void rejectsUnicodeWhitespaceAndControlsButAllowsCrLf() {
        for (String invalid : new String[]{"", " \t\n", "\u2003\u00a0", "a\u0000b", "a\u0009b", "a\u0085b"}) {
            assertThatThrownBy(() -> validator.validateContent(invalid))
                    .isInstanceOf(InvestigationNoteValidationException.class);
        }
        validator.validateContent("a\r\nb");
    }

    @Test
    void validatesCanonicalCaseIdExpectedVersionAndDeterministicListOptions() {
        assertThat(validator.validateList(CASE_ID, Map.of()).direction())
                .isEqualTo(FraudCaseNoteCommand.Direction.ASC);
        assertThat(validator.validateList(CASE_ID, Map.of(
                "page", List.of("2"), "size", List.of("100"),
                "sort", List.of("createdAt,desc")
        ))).extracting("page", "size", "direction")
                .containsExactly(2, 100, FraudCaseNoteCommand.Direction.DESC);

        for (Map<String, List<String>> invalid : List.of(
                Map.of("page", List.of("0", "1")),
                Map.of("size", List.of("0")),
                Map.of("page", List.of("-1")),
                Map.of("sort", List.of("id,asc")),
                Map.of("unknown", List.of("x"))
        )) {
            assertThatThrownBy(() -> validator.validateList(CASE_ID, invalid))
                    .isInstanceOf(InvestigationNoteValidationException.class);
        }
        assertThatThrownBy(() -> validator.validateCreate(
                CASE_ID.toUpperCase(), new InvestigationNoteCreateRequest("memo", 0L)
        )).isInstanceOf(InvestigationNoteValidationException.class);
        assertThatThrownBy(() -> validator.validateCreate(
                CASE_ID, new InvestigationNoteCreateRequest("memo", -1L)
        )).isInstanceOf(InvestigationNoteValidationException.class);
    }
}
