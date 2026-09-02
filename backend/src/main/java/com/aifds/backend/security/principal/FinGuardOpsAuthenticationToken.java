package com.aifds.backend.security.principal;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Objects;

public final class FinGuardOpsAuthenticationToken
        extends AbstractAuthenticationToken {

    private final FinGuardOpsPrincipal principal;

    public FinGuardOpsAuthenticationToken(
            FinGuardOpsPrincipal principal,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(authorities);
        this.principal = Objects.requireNonNull(
                principal,
                "principal must not be null"
        );
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public FinGuardOpsPrincipal getPrincipal() {
        return principal;
    }
}
