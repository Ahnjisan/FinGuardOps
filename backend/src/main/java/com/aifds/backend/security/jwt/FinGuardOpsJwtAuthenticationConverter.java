package com.aifds.backend.security.jwt;

import com.aifds.backend.security.principal.FinGuardOpsAuthenticationToken;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import com.aifds.backend.security.principal.FinGuardOpsRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FinGuardOpsJwtAuthenticationConverter implements
        Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        FinGuardOpsPrincipal.Type type = FinGuardOpsPrincipal.Type.valueOf(
                jwt.getClaimAsString("principal_type")
        );
        List<String> roleNames = jwt.getClaimAsStringList("roles");
        LinkedHashSet<FinGuardOpsRole> roles = new LinkedHashSet<>();
        LinkedHashSet<SimpleGrantedAuthority> authorities =
                new LinkedHashSet<>();

        for (String roleName : roleNames) {
            FinGuardOpsRole role = FinGuardOpsRole.valueOf(roleName);
            roles.add(role);
            authorities.add(new SimpleGrantedAuthority(
                    "ROLE_" + role.name()
            ));
            role.authorities().stream()
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }

        FinGuardOpsPrincipal principal = new FinGuardOpsPrincipal(
                UUID.fromString(jwt.getSubject()),
                type,
                Set.copyOf(roles)
        );
        return new FinGuardOpsAuthenticationToken(principal, authorities);
    }
}
