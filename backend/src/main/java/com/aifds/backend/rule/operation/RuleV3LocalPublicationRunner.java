package com.aifds.backend.rule.operation;

import com.aifds.backend.rule.service.RuleV3LocalPublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile("rule-v3-local-publication")
@ConditionalOnProperty(prefix = "finguardops.rule-v3-local-publication",
        name = "enabled", havingValue = "true")
public class RuleV3LocalPublicationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RuleV3LocalPublicationRunner.class);
    private final RuleV3LocalPublicationService service;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final String confirmation;
    private final String effectiveFrom;

    public RuleV3LocalPublicationRunner(RuleV3LocalPublicationService service,
            Environment environment, ConfigurableApplicationContext context,
            @Value("${finguardops.rule-v3-local-publication.confirmation:}") String confirmation,
            @Value("${finguardops.rule-v3-local-publication.effective-from:}") String effectiveFrom) {
        this.service = service;
        this.environment = environment;
        this.context = context;
        this.confirmation = confirmation;
        this.effectiveFrom = effectiveFrom;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.acceptsProfiles(Profiles.of("local", "dev", "test"))
                || environment.acceptsProfiles(Profiles.of("production", "prod"))
                || !"none".equals(environment.getProperty("spring.main.web-application-type"))
                || !"PUBLISH_RULE_V3_LOCAL".equals(confirmation)) {
            throw new IllegalStateException("Rule v3 local publication gate is closed");
        }
        var created = service.publish(Instant.parse(effectiveFrom));
        log.info("event=rule_v3_local_publication count={} effectiveFrom={}",
                created.size(), effectiveFrom);
        System.exit(SpringApplication.exit(context));
    }
}
