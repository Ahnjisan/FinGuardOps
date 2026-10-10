package com.aifds.backend.persistence;

import com.aifds.backend.rule.contract.RulePolicyVersion;
import com.aifds.backend.rule.repository.RuleVersionRepository;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import com.aifds.backend.rule.service.RuleV2LocalPublicationService;
import com.aifds.backend.rule.service.RuleV3LocalPublicationService;
import com.aifds.backend.rule.client.dto.RuleVersionSnapshotRequest;
import com.aifds.backend.rule.client.dto.RuleLifecycleStatus;
import com.aifds.backend.rule.client.dto.RuleVersionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuleV3LocalPublicationIntegrationTest extends PostgresqlIntegrationTestSupport {
    private static final Instant V1 = Instant.parse("2999-01-01T00:00:00Z");
    private static final Instant V2 = Instant.parse("3000-01-01T00:00:00Z");
    private static final Instant V3 = Instant.parse("3001-01-01T00:00:00Z");

    @Autowired RuleV1DefaultRuleSetPublicationService v1;
    @Autowired RuleV2LocalPublicationService v2;
    @Autowired RuleV3LocalPublicationService v3;
    @Autowired RuleVersionRepository versions;

    @Test
    void publishesExactFiveVersionSetAtFutureCutoffWithoutRewritingHistory() {
        v1.publish(V1);
        v2.publish(V2);
        var old = versions.findAllExecutableVersions(V2);
        var created = v3.publish(V3);
        assertThat(created).hasSize(5);
        assertThat(versions.findAllExecutableVersions(V3.minusSeconds(1)))
                .hasSize(4).allMatch(item -> item.getVersionNumber() == 2);
        var active = versions.findAllExecutableVersions(V3);
        assertThat(active).hasSize(5);
        var snapshots = active.stream().map(item -> new RuleVersionSnapshotRequest(
                item.getFraudRule().getFraudRuleId(),
                item.getFraudRule().getRuleCode(), RuleLifecycleStatus.ACTIVE,
                item.getRuleVersionId(),
                item.getVersionNumber(), RuleVersionStatus.PUBLISHED,
                item.getReasonCode(), item.getWeight(), item.getConditionDefinition(),
                item.getEffectiveFrom(), item.getEffectiveTo())).toList();
        assertThat(RulePolicyVersion.from(snapshots)).isEqualTo(3);
        assertThat(old).hasSize(4);
        assertThatThrownBy(() -> v3.publish(V3)).isInstanceOf(RuntimeException.class);
        assertThat(versions.findAllExecutableVersions(V3)).hasSize(5);
    }
}
