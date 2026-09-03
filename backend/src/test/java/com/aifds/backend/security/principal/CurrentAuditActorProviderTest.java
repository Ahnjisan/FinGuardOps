package com.aifds.backend.security.principal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentAuditActorProviderTest {

    private static final UUID USER_SUBJECT = UUID.fromString(
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"
    );

    private final CurrentAuditActorProvider provider =
            new CurrentAuditActorProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCanonicalUuidV4OnlyForAuthenticatedUserToken() {
        authenticate(token(FinGuardOpsPrincipal.Type.USER, USER_SUBJECT));

        assertThat(provider.currentUserSubject()).isEqualTo(USER_SUBJECT);
        assertThat(provider.currentUserSubject().toString())
                .isEqualTo("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
    }

    @Test
    void rejectsMissingUnauthenticatedServiceAndInvalidUuidWithoutFallback() {
        assertDenied();

        FinGuardOpsAuthenticationToken unauthenticated = token(
                FinGuardOpsPrincipal.Type.USER,
                USER_SUBJECT
        );
        unauthenticated.setAuthenticated(false);
        authenticate(unauthenticated);
        assertDenied();

        authenticate(token(FinGuardOpsPrincipal.Type.SERVICE, USER_SUBJECT));
        assertDenied();

        authenticate(token(
                FinGuardOpsPrincipal.Type.USER,
                UUID.fromString("2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001")
        ));
        assertDenied();
    }

    @Test
    void rejectsTestingAuthenticationTokenAndDoesNotExposePrincipal() {
        authenticate(new TestingAuthenticationToken(
                "credential-sentinel-user",
                "credential-sentinel-secret",
                "case:workflow:write"
        ));

        assertThatThrownBy(provider::currentUserSubject)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("An authenticated USER principal is required")
                .hasMessageNotContaining("credential-sentinel");
    }

    private FinGuardOpsAuthenticationToken token(
            FinGuardOpsPrincipal.Type type,
            UUID subject
    ) {
        return new FinGuardOpsAuthenticationToken(
                new FinGuardOpsPrincipal(subject, type, Set.of()),
                List.of(new SimpleGrantedAuthority("case:workflow:write"))
        );
    }

    private void authenticate(org.springframework.security.core.Authentication value) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(value);
        SecurityContextHolder.setContext(context);
    }

    private void assertDenied() {
        assertThatThrownBy(provider::currentUserSubject)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("An authenticated USER principal is required");
    }
}
