package com.aifds.backend.rule.operation;

import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationResult;
import com.aifds.backend.rule.service.RuleV1DefaultRuleSetPublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The profile and enabled property are only the bean-creation gate.
 * {@link #run(ApplicationArguments)} intentionally performs the environment,
 * non-web, confirmation, and time checks as a second fail-fast safety gate.
 */
@Component
@Profile(RuleV1DefaultRuleSetPublicationRunner.PUBLICATION_PROFILE)
@ConditionalOnProperty(
        prefix = RuleV1DefaultRuleSetPublicationRunner.PROPERTY_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class RuleV1DefaultRuleSetPublicationRunner implements ApplicationRunner {

    public static final String PUBLICATION_PROFILE =
            "rule-v1-default-publication";
    public static final String PROPERTY_PREFIX =
            "finguardops.rule-v1-default-publication";
    public static final String REQUIRED_CONFIRMATION =
            "PUBLISH_RULE_V1_DEFAULT_V1";

    private static final Logger log = LoggerFactory.getLogger(
            RuleV1DefaultRuleSetPublicationRunner.class
    );
    private static final Profiles PRODUCTION_PROFILES =
            Profiles.of("production", "prod");
    private static final Profiles APPROVED_ENVIRONMENT_PROFILES =
            Profiles.of("local", "dev", "test");
    private static final Profiles PUBLICATION_PROFILES =
            Profiles.of(PUBLICATION_PROFILE);

    private final RuleV1DefaultRuleSetPublicationService publicationService;
    private final Environment environment;
    private final Clock clock;
    private final String confirmation;
    private final String effectiveFromValue;

    public RuleV1DefaultRuleSetPublicationRunner(
            RuleV1DefaultRuleSetPublicationService publicationService,
            Environment environment,
            Clock clock,
            @Value("${" + PROPERTY_PREFIX + ".confirmation:}")
            String confirmation,
            @Value("${" + PROPERTY_PREFIX + ".effective-from:}")
            String effectiveFromValue
    ) {
        this.publicationService = publicationService;
        this.environment = environment;
        this.clock = clock;
        this.confirmation = confirmation;
        this.effectiveFromValue = effectiveFromValue;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateEnvironment();
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Rule v1 default publication confirmation does not match"
            );
        }
        Instant effectiveFrom = parseEffectiveFrom(effectiveFromValue);
        Instant validationTime = clock.instant();
        if (!effectiveFrom.isAfter(validationTime)) {
            throw new IllegalArgumentException(
                    "Rule v1 default effectiveFrom must be in the future"
            );
        }

        RuleV1DefaultRuleSetPublicationResult result =
                publicationService.publish(effectiveFrom);
        log.info(
                "event=rule_v1_default_rule_set_publication "
                        + "outcome={} ruleVersionIds={} effectiveFrom={} "
                        + "publishedAt={} ruleSetVersion={}",
                result.outcome(),
                result.ruleVersionIds(),
                result.effectiveFrom(),
                result.publishedAt(),
                result.ruleSetVersion()
        );
    }

    private void validateEnvironment() {
        if (environment.acceptsProfiles(PRODUCTION_PROFILES)) {
            throw new IllegalStateException(
                    "Rule v1 default publication is forbidden in production"
            );
        }
        if (!environment.acceptsProfiles(PUBLICATION_PROFILES)
                || !environment.acceptsProfiles(
                APPROVED_ENVIRONMENT_PROFILES
        )) {
            throw new IllegalStateException(
                    "Rule v1 default publication requires its operation profile "
                            + "and a local, dev, or test profile"
            );
        }
        String webApplicationType = environment.getProperty(
                "spring.main.web-application-type"
        );
        if (!"none".equalsIgnoreCase(webApplicationType)) {
            throw new IllegalStateException(
                    "Rule v1 default publication requires "
                            + "spring.main.web-application-type=none"
            );
        }
    }

    private Instant parseEffectiveFrom(String value) {
        if (value == null || value.isBlank() || !value.endsWith("Z")) {
            throw new IllegalArgumentException(
                    "Rule v1 default effectiveFrom must be a canonical UTC Instant"
            );
        }
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)
                    || parsed.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "Rule v1 default effectiveFrom must be canonical UTC "
                                + "with at most microsecond precision"
                );
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Rule v1 default effectiveFrom must be a canonical UTC Instant",
                    exception
            );
        }
    }
}
