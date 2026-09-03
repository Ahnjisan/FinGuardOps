package com.aifds.backend.security.jwt;

import com.aifds.backend.security.config.FinGuardOpsSecurityProperties;
import com.aifds.backend.security.principal.FinGuardOpsPrincipal;
import com.aifds.backend.security.principal.FinGuardOpsRole;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FinGuardOpsJwtValidator
        implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token",
            "The token does not satisfy the required contract",
            null
    );
    private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    private final URI issuer;
    private final Clock clock;

    public FinGuardOpsJwtValidator(FinGuardOpsSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public FinGuardOpsJwtValidator(
            FinGuardOpsSecurityProperties properties,
            Clock clock
    ) {
        this(properties.issuer(), clock);
    }

    FinGuardOpsJwtValidator(URI issuer, Clock clock) {
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!hasStrictHeader(jwt)
                || !hasStrictIssuerAndAudience(jwt)
                || !hasStrictSubject(jwt)
                || !hasStrictPrincipalAndRoles(jwt)
                || !hasValidTimeWindow(jwt)) {
            return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private boolean hasStrictHeader(Jwt jwt) {
        Map<String, Object> headers = jwt.getHeaders();
        Object kid = headers.get("kid");
        return "RS256".equals(String.valueOf(headers.get("alg")))
                && kid instanceof String value
                && !value.isBlank()
                && !headers.containsKey("jku")
                && !headers.containsKey("x5u");
    }

    private boolean hasStrictIssuerAndAudience(Jwt jwt) {
        return issuer.toString().equals(jwt.getClaimAsString("iss"))
                && jwt.getAudience().equals(List.of(
                FinGuardOpsSecurityProperties.AUDIENCE
        ));
    }

    private boolean hasStrictSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || !CANONICAL_UUID_V4.matcher(subject).matches()) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(subject);
            return parsed.version() == 4
                    && parsed.variant() == 2
                    && parsed.toString().equals(subject);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean hasStrictPrincipalAndRoles(Jwt jwt) {
        Object principalTypeClaim = jwt.getClaims().get("principal_type");
        Object rolesClaim = jwt.getClaims().get("roles");
        if (!(principalTypeClaim instanceof String principalTypeValue)
                || !(rolesClaim instanceof List<?> roleValues)
                || roleValues.isEmpty()) {
            return false;
        }

        FinGuardOpsPrincipal.Type principalType;
        try {
            principalType = FinGuardOpsPrincipal.Type.valueOf(
                    principalTypeValue
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }

        Set<String> distinctRoles = new HashSet<>();
        for (Object roleValue : roleValues) {
            if (!(roleValue instanceof String roleName)
                    || !distinctRoles.add(roleName)) {
                return false;
            }
            try {
                if (FinGuardOpsRole.valueOf(roleName).principalType()
                        != principalType) {
                    return false;
                }
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return true;
    }

    private boolean hasValidTimeWindow(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            return false;
        }

        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.compareTo(FinGuardOpsSecurityProperties.MAX_TOKEN_LIFETIME)
                > 0) {
            return false;
        }

        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(
                FinGuardOpsSecurityProperties.CLOCK_SKEW
        ))) {
            return false;
        }
        if (expiresAt.isBefore(now.minus(
                FinGuardOpsSecurityProperties.CLOCK_SKEW
        ))) {
            return false;
        }

        Instant notBefore = jwt.getNotBefore();
        return notBefore == null || !notBefore.isAfter(now.plus(
                FinGuardOpsSecurityProperties.CLOCK_SKEW
        ));
    }
}
