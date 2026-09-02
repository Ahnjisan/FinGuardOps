package com.aifds.backend.security.principal;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record FinGuardOpsPrincipal(
        UUID subject,
        Type type,
        Set<FinGuardOpsRole> roles
) implements Principal {

    public FinGuardOpsPrincipal {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(type, "type must not be null");
        roles = Set.copyOf(Objects.requireNonNull(
                roles,
                "roles must not be null"
        ));
    }

    @Override
    public String getName() {
        return subject.toString();
    }

    public enum Type {
        USER,
        SERVICE
    }
}
