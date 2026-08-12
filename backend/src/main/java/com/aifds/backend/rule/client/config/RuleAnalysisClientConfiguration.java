package com.aifds.backend.rule.client.config;

import com.aifds.backend.rule.client.RuleAnalysisHttpClient;
import com.aifds.backend.rule.client.RuleAnalysisResponseValidator;
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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiServiceProperties.class)
public class RuleAnalysisClientConfiguration {

    public static final String OBJECT_MAPPER_BEAN = "ruleAnalysisObjectMapper";
    public static final String JDK_HTTP_CLIENT_BEAN = "ruleAnalysisJdkHttpClient";
    public static final String REST_CLIENT_BEAN = "ruleAnalysisRestClient";

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern UTC_Z_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"
    );

    @Bean(OBJECT_MAPPER_BEAN)
    public ObjectMapper ruleAnalysisObjectMapper(
            Jackson2ObjectMapperBuilder applicationObjectMapperBuilder
    ) {
        ObjectMapper mapper = applicationObjectMapperBuilder.build();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        mapper.setConfig(mapper.getDeserializationConfig().without(
                MapperFeature.ALLOW_COERCION_OF_SCALARS,
                MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS
        ));
        mapper.setConfig(mapper.getSerializationConfig().without(
                MapperFeature.ALLOW_COERCION_OF_SCALARS,
                MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS
        ));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

        SimpleModule wireTypes = new SimpleModule("rule-analysis-wire-types");
        wireTypes.addDeserializer(UUID.class, new CanonicalUuidV4Deserializer());
        wireTypes.addDeserializer(Instant.class, new UtcInstantDeserializer());
        wireTypes.addSerializer(Instant.class, new UtcInstantSerializer());
        mapper.registerModule(wireTypes);
        return mapper;
    }

    @Bean(JDK_HTTP_CLIENT_BEAN)
    public HttpClient ruleAnalysisJdkHttpClient(AiServiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(REST_CLIENT_BEAN)
    public RestClient ruleAnalysisRestClient(
            AiServiceProperties properties,
            @Qualifier(JDK_HTTP_CLIENT_BEAN) HttpClient httpClient,
            @Qualifier(OBJECT_MAPPER_BEAN) ObjectMapper objectMapper
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.removeIf(
                            MappingJackson2HttpMessageConverter.class::isInstance
                    );
                    converters.add(0, new MappingJackson2HttpMessageConverter(
                            objectMapper
                    ));
                })
                .build();
    }

    @Bean
    public RuleAnalysisResponseValidator ruleAnalysisResponseValidator() {
        return new RuleAnalysisResponseValidator();
    }

    @Bean
    public RuleAnalysisHttpClient ruleAnalysisHttpClient(
            @Qualifier(REST_CLIENT_BEAN) RestClient restClient,
            @Qualifier(OBJECT_MAPPER_BEAN) ObjectMapper objectMapper,
            RuleAnalysisResponseValidator validator
    ) {
        return new RuleAnalysisHttpClient(restClient, objectMapper, validator);
    }

    private static final class CanonicalUuidV4Deserializer
            extends JsonDeserializer<UUID> {

        @Override
        public UUID deserialize(
                JsonParser parser,
                DeserializationContext context
        ) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw JsonMappingException.from(
                        parser,
                        "UUID must be a string"
                );
            }
            String value = parser.getText();
            if (!UUID_V4_PATTERN.matcher(value).matches()) {
                throw context.weirdStringException(
                        value,
                        UUID.class,
                        "UUID must be canonical lowercase version 4"
                );
            }
            UUID parsed = UUID.fromString(value);
            if (parsed.version() != 4
                    || parsed.variant() != 2
                    || !parsed.toString().equals(value)) {
                throw context.weirdStringException(
                        value,
                        UUID.class,
                        "UUID must be RFC 4122 version 4"
                );
            }
            return parsed;
        }
    }

    private static final class UtcInstantDeserializer
            extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(
                JsonParser parser,
                DeserializationContext context
        ) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw JsonMappingException.from(
                        parser,
                        "timestamp must be a string"
                );
            }
            String value = parser.getText();
            if (!UTC_Z_PATTERN.matcher(value).matches()) {
                throw context.weirdStringException(
                        value,
                        Instant.class,
                        "timestamp must use UTC Z with at most six fractional digits"
                );
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                throw context.weirdStringException(
                        value,
                        Instant.class,
                        "timestamp must be a valid UTC instant"
                );
            }
        }
    }

    private static final class UtcInstantSerializer
            extends JsonSerializer<Instant> {

        @Override
        public void serialize(
                Instant value,
                JsonGenerator generator,
                SerializerProvider serializers
        ) throws IOException {
            if (value.getNano() % 1_000 != 0) {
                throw JsonMappingException.from(
                        generator,
                        "timestamp precision must not exceed microseconds"
                );
            }
            generator.writeString(value.toString());
        }
    }
}
