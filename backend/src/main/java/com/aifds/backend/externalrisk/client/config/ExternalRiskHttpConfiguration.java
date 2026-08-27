package com.aifds.backend.externalrisk.client.config;

import com.aifds.backend.detection.service.RuleAnalysisOrchestrationService;
import com.aifds.backend.externalrisk.client.ExternalRiskHttpAdapter;
import com.aifds.backend.externalrisk.client.ExternalRiskHttpMapper;
import com.aifds.backend.externalrisk.mock.ExternalRiskMockConfiguration;
import com.aifds.backend.externalrisk.service.ExternalRiskLookupCommandReader;
import com.aifds.backend.externalrisk.service.ExternalRiskPolicyService;
import com.aifds.backend.externalrisk.service.ExternalRiskRuleAnalysisCoordinator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExternalRiskHttpProperties.class)
public class ExternalRiskHttpConfiguration {

    public static final String HTTP_PROFILE = "external-risk-http";
    public static final String PROPERTY_PREFIX = "finguardops.external-risk.http";
    public static final String OBJECT_MAPPER_BEAN = "externalRiskObjectMapper";
    public static final String JDK_HTTP_CLIENT_BEAN = "externalRiskJdkHttpClient";
    public static final String REST_CLIENT_BEAN = "externalRiskRestClient";

    private static final Profiles PRODUCTION_PROFILES =
            Profiles.of("production", "prod");
    private static final Profiles APPROVED_NON_PRODUCTION_PROFILES =
            Profiles.of("local", "dev", "test");
    private static final Profiles HTTP_PROFILE_ONLY = Profiles.of(HTTP_PROFILE);
    private static final Pattern UTC_Z_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"
    );

    public ExternalRiskHttpConfiguration(
            Environment environment,
            ExternalRiskHttpProperties properties
    ) {
        boolean production = environment.acceptsProfiles(PRODUCTION_PROFILES);
        boolean approvedNonProduction = environment.acceptsProfiles(
                APPROVED_NON_PRODUCTION_PROFILES
        );
        boolean httpProfile = environment.acceptsProfiles(HTTP_PROFILE_ONLY);
        boolean mockEnabled = environment.getProperty(
                ExternalRiskMockConfiguration.PROPERTY_PREFIX + ".enabled",
                Boolean.class,
                false
        );

        if (production && !properties.enabled()) {
            throw new IllegalStateException(
                    "External Risk HTTP Provider is required in production"
            );
        }
        if (httpProfile && !production && !approvedNonProduction) {
            throw new IllegalStateException(
                    "External Risk HTTP profile requires an approved environment"
            );
        }
        if (properties.enabled() && mockEnabled) {
            throw new IllegalStateException(
                    "External Risk Mock and HTTP Provider are mutually exclusive"
            );
        }
        if (properties.enabled()
                && !production
                && (!approvedNonProduction || !httpProfile)) {
            throw new IllegalStateException(
                    "External Risk HTTP Provider requires its dedicated profile"
            );
        }
        if (properties.enabled()) {
            validateEnvironmentBaseUrl(properties.baseUrl(), production);
        }
    }

    @Bean(OBJECT_MAPPER_BEAN)
    @HttpProviderEnabled
    public ExternalRiskJsonCodec externalRiskObjectMapper(
            Jackson2ObjectMapperBuilder applicationObjectMapperBuilder
    ) {
        ObjectMapper mapper = applicationObjectMapperBuilder.build();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        mapper.setConfig(mapper.getDeserializationConfig().without(
                MapperFeature.ALLOW_COERCION_OF_SCALARS,
                MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS
        ));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);
        mapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule(
                "external-risk-wire-types"
        ).addDeserializer(Instant.class, new CanonicalInstantDeserializer())
                .addSerializer(Instant.class, new CanonicalInstantSerializer()));
        return new ExternalRiskJsonCodec(mapper);
    }

    @Bean(JDK_HTTP_CLIENT_BEAN)
    @HttpProviderEnabled
    public HttpClient externalRiskJdkHttpClient(
            ExternalRiskHttpProperties properties
    ) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(REST_CLIENT_BEAN)
    @HttpProviderEnabled
    public RestClient externalRiskRestClient(
            ExternalRiskHttpProperties properties,
            @Qualifier(JDK_HTTP_CLIENT_BEAN) HttpClient httpClient,
            @Qualifier(OBJECT_MAPPER_BEAN) ExternalRiskJsonCodec jsonCodec
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .messageConverters(converters -> {
                    converters.removeIf(
                            MappingJackson2HttpMessageConverter.class::isInstance
                    );
                    converters.add(0, new MappingJackson2HttpMessageConverter(
                            jsonCodec.objectMapper()
                    ));
                })
                .build();
    }

    @Bean
    @HttpProviderEnabled
    public ExternalRiskHttpMapper externalRiskHttpMapper() {
        return new ExternalRiskHttpMapper();
    }

    @Bean
    @HttpProviderEnabled
    public ExternalRiskHttpAdapter externalRiskHttpAdapter(
            @Qualifier(REST_CLIENT_BEAN) RestClient restClient,
            @Qualifier(OBJECT_MAPPER_BEAN) ExternalRiskJsonCodec jsonCodec,
            ExternalRiskHttpMapper mapper,
            ExternalRiskHttpProperties properties
    ) {
        return new ExternalRiskHttpAdapter(
                restClient,
                jsonCodec.objectMapper(),
                mapper,
                properties.maxResponseBytes()
        );
    }

    @Bean("externalRiskHttpPolicyService")
    @HttpProviderEnabled
    public ExternalRiskPolicyService externalRiskPolicyService(
            ExternalRiskHttpAdapter adapter,
            Clock clock
    ) {
        return new ExternalRiskPolicyService(adapter, clock);
    }

    @Bean("externalRiskHttpRuleAnalysisCoordinator")
    @HttpProviderEnabled
    public ExternalRiskRuleAnalysisCoordinator externalRiskRuleAnalysisCoordinator(
            ExternalRiskLookupCommandReader commandReader,
            ExternalRiskPolicyService policyService,
            RuleAnalysisOrchestrationService orchestrationService
    ) {
        return new ExternalRiskRuleAnalysisCoordinator(
                commandReader,
                policyService,
                orchestrationService
        );
    }

    private static void validateEnvironmentBaseUrl(
            URI baseUrl,
            boolean production
    ) {
        if (production) {
            if (!"https".equalsIgnoreCase(baseUrl.getScheme())) {
                throw new IllegalStateException(
                        "External Risk production baseUrl must use HTTPS"
                );
            }
            return;
        }
        if ("https".equalsIgnoreCase(baseUrl.getScheme())) {
            return;
        }
        String host = baseUrl.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = "localhost".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || isIpv4Loopback(host);
        if (!loopback) {
            throw new IllegalStateException(
                    "External Risk non-production HTTP baseUrl must be loopback"
            );
        }
    }

    private static boolean isIpv4Loopback(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    public static final class ExternalRiskJsonCodec {

        private final ObjectMapper objectMapper;

        private ExternalRiskJsonCodec(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        public ObjectMapper objectMapper() {
            return objectMapper;
        }
    }

    @Profile({HTTP_PROFILE, "prod", "production"})
    @ConditionalOnProperty(
            prefix = PROPERTY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface HttpProviderEnabled {
    }

    private static final class CanonicalInstantDeserializer
            extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(
                JsonParser parser,
                DeserializationContext context
        ) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw JsonMappingException.from(parser, "timestamp must be a string");
            }
            String value = parser.getText();
            if (!UTC_Z_PATTERN.matcher(value).matches()) {
                throw context.weirdStringException(
                        value,
                        Instant.class,
                        "timestamp must be canonical UTC with microsecond precision"
                );
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                throw context.weirdStringException(
                        value,
                        Instant.class,
                        "timestamp must be a valid instant"
                );
            }
        }
    }

    private static final class CanonicalInstantSerializer
            extends JsonSerializer<Instant> {

        @Override
        public void serialize(
                Instant value,
                JsonGenerator generator,
                SerializerProvider serializers
        ) throws IOException {
            if (value == null || value.getNano() % 1_000 != 0) {
                throw JsonMappingException.from(
                        generator,
                        "timestamp precision must not exceed microseconds"
                );
            }
            generator.writeString(value.toString());
        }
    }
}
