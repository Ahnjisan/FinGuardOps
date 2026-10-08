package com.aifds.backend.persistence;

import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import com.aifds.backend.rule.service.RuleV2LocalPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuleV2LocalPublicationIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final Instant V1 = Instant.parse("2999-01-01T00:00:00Z");
    private static final Instant V2 = Instant.parse("3000-01-01T00:00:00Z");

    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleVersionRepository versions;

    @Test
    void switchesAllFourAtCutoffAndRetainsPriorPublishedVersions() {
        v1.publish(V1);
        var prior = versions.findAllExecutableVersions(V1);
        assertThat(prior).hasSize(4).allMatch(item -> item.getVersionNumber() == 1);

        var created = v2.publish(V2);
        assertThat(created).hasSize(4).allMatch(item -> item.getVersionNumber() == 2);
        assertThat(versions.findAllExecutableVersions(V2.minusSeconds(1)))
                .hasSize(4).allMatch(item -> item.getVersionNumber() == 1);
        assertThat(versions.findAllExecutableVersions(V2))
                .hasSize(4).allMatch(item -> item.getVersionNumber() == 2);
        assertThat(versions.findAllExecutableVersions(V1.minusSeconds(1))).isEmpty();
        for (var old : prior) {
            var stored = versions.findByRuleVersionId(old.getRuleVersionId()).orElseThrow();
            assertThat(stored.getEffectiveTo()).isEqualTo(V2);
            assertThat(stored.getVersionNumber()).isEqualTo(1);
        }
        assertThatThrownBy(() -> v2.publish(V2)).isInstanceOf(RuntimeException.class);
        assertThat(versions.findAllExecutableVersions(V2)).hasSize(4);
    }
}
