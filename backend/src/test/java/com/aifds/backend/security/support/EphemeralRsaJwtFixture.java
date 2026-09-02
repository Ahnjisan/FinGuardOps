package com.aifds.backend.security.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EphemeralRsaJwtFixture {

    public static final String ISSUER = "https://issuer.test/finguardops";
    public static final String SUBJECT =
            "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";

    private final String kid;
    private final RSAKey rsaKey;

    private EphemeralRsaJwtFixture(String kid, RSAKey rsaKey) {
        this.kid = kid;
        this.rsaKey = rsaKey;
    }

    public static EphemeralRsaJwtFixture create(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(kid)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new EphemeralRsaJwtFixture(kid, key);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create ephemeral RSA fixture",
                    exception
            );
        }
    }

    public String kid() {
        return kid;
    }

    public RSAKey publicJwk() {
        return rsaKey.toPublicJWK();
    }

    public String publicJwkSetJson() {
        return JSONObjectUtils.toJSONString(
                new JWKSet(publicJwk()).toJSONObject()
        );
    }

    public Map<String, Object> validClaims(
            String principalType,
            List<String> roles
    ) {
        Instant now = Instant.now();
        LinkedHashMap<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of("finguardops-backend-api"));
        claims.put("sub", SUBJECT);
        claims.put("principal_type", principalType);
        claims.put("roles", roles);
        claims.put("iat", now.minusSeconds(5).getEpochSecond());
        claims.put("exp", now.plusSeconds(300).getEpochSecond());
        return claims;
    }

    public String validUserToken() {
        return sign(
                JWSAlgorithm.RS256,
                kid,
                Map.of(),
                validClaims("USER", List.of("FDS_VIEWER"))
        );
    }

    public String sign(Map<String, Object> claims) {
        return sign(JWSAlgorithm.RS256, kid, Map.of(), claims);
    }

    public String sign(
            JWSAlgorithm algorithm,
            String headerKid,
            Map<String, Object> extraHeaders,
            Map<String, Object> claims
    ) {
        try {
            JWSHeader.Builder header = new JWSHeader.Builder(algorithm);
            if (headerKid != null) {
                header.keyID(headerKid);
            }
            extraHeaders.forEach((name, value) -> {
                if ("jku".equals(name)) {
                    header.jwkURL(URI.create(String.valueOf(value)));
                } else if ("x5u".equals(name)) {
                    header.x509CertURL(URI.create(String.valueOf(value)));
                } else {
                    header.customParam(name, value);
                }
            });
            JWSObject jwt = new JWSObject(
                    header.build(),
                    new Payload(claims)
            );
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not sign ephemeral JWT",
                    exception
            );
        }
    }
}
