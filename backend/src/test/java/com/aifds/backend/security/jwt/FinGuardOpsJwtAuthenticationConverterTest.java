package com.aifds.backend.security.jwt;

import com.aifds.backend.security.principal.FinGuardOpsAuthenticationToken;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import com.aifds.backend.security.principal.FinGuardOpsRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifds.backend.security.principal.FinGuardOpsAuthority.AI_OPERATIONS_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.AI_REPORT_CREATE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.AI_REPORT_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.AI_USAGE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.BEHAVIOR_EVENT_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.BEHAVIOR_EVENT_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_AUDIT_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_NOTE_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_RESOLUTION_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.CASE_WORKFLOW_WRITE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.DETECTION_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.PLATFORM_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.RECOVERY_EXECUTE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.RECOVERY_INSPECT;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.RULE_VERSION_PUBLISH;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.RULE_VERSION_READ;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_INTAKE;
import static com.aifds.backend.security.principal.FinGuardOpsAuthority.TRANSACTION_READ;
import static org.assertj.core.api.Assertions.assertThat;

class FinGuardOpsJwtAuthenticationConverterTest {

    private final FinGuardOpsJwtAuthenticationConverter converter =
            new FinGuardOpsJwtAuthenticationConverter();

    @Test
    void createsImmutableUserPrincipalAndRoleDerivedAuthorities() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                "USER",
                List.of("FDS_ANALYST", "PLATFORM_ADMIN")
        ));

        assertThat(authentication).isInstanceOf(
                FinGuardOpsAuthenticationToken.class
        );
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getCredentials()).isNull();
        FinGuardOpsPrincipal principal =
                (FinGuardOpsPrincipal) authentication.getPrincipal();
        assertThat(principal.type()).isEqualTo(FinGuardOpsPrincipal.Type.USER);
        assertThat(principal.roles()).containsExactlyInAnyOrder(
                FinGuardOpsRole.FDS_ANALYST,
                FinGuardOpsRole.PLATFORM_ADMIN
        );
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains(
                        "ROLE_FDS_ANALYST",
                        "transaction:read",
                        "case:workflow:write",
                        "ROLE_PLATFORM_ADMIN",
                        "platform:read"
                )
                .doesNotContain(
                        "case:resolution:write",
                        "transaction:intake"
                );
    }

    @Test
    void createsServicePrincipalWithOnlyServiceAuthorities() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                "SERVICE",
                List.of("TRANSACTION_INGESTOR")
        ));

        FinGuardOpsPrincipal principal =
                (FinGuardOpsPrincipal) authentication.getPrincipal();
        assertThat(principal.type())
                .isEqualTo(FinGuardOpsPrincipal.Type.SERVICE);
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly(
                        "ROLE_TRANSACTION_INGESTOR",
                        "transaction:intake"
                );
    }

    @Test
    void keepsEveryApprovedRoleMappingExactWithoutAutomaticInheritance() {
        Set<String> viewer = Set.of(
                TRANSACTION_READ,
                BEHAVIOR_EVENT_READ,
                DETECTION_READ,
                CASE_READ,
                CASE_NOTE_READ,
                CASE_AUDIT_READ,
                AI_REPORT_READ
        );
        Map<FinGuardOpsRole, Set<String>> expected = Map.of(
                FinGuardOpsRole.FDS_VIEWER,
                viewer,
                FinGuardOpsRole.FDS_ANALYST,
                union(viewer, Set.of(
                        CASE_WORKFLOW_WRITE,
                        CASE_NOTE_WRITE,
                        AI_REPORT_CREATE
                )),
                FinGuardOpsRole.FDS_APPROVER,
                union(viewer, Set.of(CASE_RESOLUTION_WRITE)),
                FinGuardOpsRole.RULE_OPERATOR,
                Set.of(RULE_VERSION_READ, RULE_VERSION_PUBLISH),
                FinGuardOpsRole.RECOVERY_OPERATOR,
                Set.of(RECOVERY_INSPECT, RECOVERY_EXECUTE),
                FinGuardOpsRole.PLATFORM_ADMIN,
                Set.of(PLATFORM_READ, AI_OPERATIONS_READ, AI_USAGE_READ),
                FinGuardOpsRole.TRANSACTION_INGESTOR,
                Set.of(TRANSACTION_INTAKE),
                FinGuardOpsRole.BEHAVIOR_INGESTOR,
                Set.of(BEHAVIOR_EVENT_INTAKE)
        );

        assertThat(FinGuardOpsRole.values()).containsExactlyInAnyOrderElementsOf(
                expected.keySet()
        );
        expected.forEach((role, authorities) -> assertThat(role.authorities())
                .as(role.name())
                .containsExactlyInAnyOrderElementsOf(authorities));
    }

    private Set<String> union(Set<String> first, Set<String> second) {
        java.util.LinkedHashSet<String> union =
                new java.util.LinkedHashSet<>(first);
        union.addAll(second);
        return Set.copyOf(union);
    }

    private Jwt jwt(String principalType, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("not-a-stored-credential")
                .header("alg", "RS256")
                .header("kid", "test-key")
                .issuer("https://issuer.test/finguardops")
                .audience(List.of("finguardops-backend-api"))
                .subject("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001")
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("principal_type", principalType)
                .claim("roles", roles)
                .build();
    }
}
