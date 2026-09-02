package com.aifds.backend.security.jwt;

import com.aifds.backend.security.principal.FinGuardOpsAuthenticationToken;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import com.aifds.backend.security.principal.FinGuardOpsRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

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
