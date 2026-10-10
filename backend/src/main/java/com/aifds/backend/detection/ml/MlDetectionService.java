package com.aifds.backend.detection.ml;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import com.aifds.backend.behavior.entity.BehaviorEventType;
import com.aifds.backend.behavior.repository.BehaviorEventRepository;
import com.aifds.backend.transaction.entity.FinancialTransaction;
import com.aifds.backend.transaction.repository.FinancialTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MlDetectionService {
    private static final int MAX_EVENTS = 1000;
    private static final Set<BehaviorEventType> TYPES = EnumSet.of(
            BehaviorEventType.DEVICE_REGISTERED, BehaviorEventType.PASSWORD_CHANGED,
            BehaviorEventType.TRANSFER_LIMIT_CHANGED, BehaviorEventType.BENEFICIARY_REGISTERED);
    private final boolean enabled;
    private final Instant effectiveFrom;
    private final FinancialTransactionRepository transactions;
    private final BehaviorEventRepository events;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;

    @Autowired
    public MlDetectionService(
            @Value("${finguardops.ml.enabled:false}") boolean enabled,
            @Value("${finguardops.ml.effective-from:2026-10-09T15:00:00Z}") Instant effectiveFrom,
            @Value("${finguardops.ml.base-url:http://localhost:8000}") String baseUrl,
            FinancialTransactionRepository transactions,
            BehaviorEventRepository events,
            @Qualifier("ruleAnalysisObjectMapper") ObjectMapper mapper,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this(enabled, effectiveFrom, baseUrl, transactions, events, mapper,
                meterRegistryProvider.getIfAvailable());
    }

    private MlDetectionService(boolean enabled, Instant effectiveFrom, String baseUrl,
                               FinancialTransactionRepository transactions,
                               BehaviorEventRepository events, ObjectMapper mapper,
                               MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.effectiveFrom = effectiveFrom;
        this.transactions = transactions;
        this.events = events;
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public MlDetectionService(boolean enabled, Instant effectiveFrom, String baseUrl,
                              FinancialTransactionRepository transactions,
                              BehaviorEventRepository events, ObjectMapper mapper) {
        this(enabled, effectiveFrom, baseUrl, transactions, events, mapper,
                (MeterRegistry) null);
    }

    public boolean appliesAt(Instant cutoff) {
        return enabled && !cutoff.isBefore(effectiveFrom);
    }

    public Instant cutoffFor(UUID transactionId) {
        return transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new MlDetectionException("ML_INPUT_UNAVAILABLE"))
                .getOccurredAt();
    }

    public MlResult infer(UUID transactionId, Instant cutoff,
                          String scoringPolicyVersion, String modelVersion) {
        long started = System.nanoTime();
        String category = "NONE";
        try {
            return inferValidated(transactionId, cutoff, scoringPolicyVersion, modelVersion);
        } catch (MlDetectionException exception) {
            category = exception.code();
            throw exception;
        } catch (RuntimeException exception) {
            category = "ML_INFERENCE_FAILED";
            throw exception;
        } finally {
            recordInference(category, System.nanoTime() - started);
        }
    }

    private MlResult inferValidated(UUID transactionId, Instant cutoff,
                                    String scoringPolicyVersion, String modelVersion) {
        if (!(MlDetectionPolicy.POLICY_VERSION.equals(scoringPolicyVersion)
                || MlDetectionPolicy.SCN003_POLICY_VERSION.equals(scoringPolicyVersion))
                || !MlDetectionPolicy.modelForCutoff(cutoff).equals(modelVersion)) {
            throw new MlDetectionException("ML_PINNED_VERSION_MISMATCH");
        }
        String modelSha256 = MlDetectionPolicy.shaForModel(modelVersion);
        FinancialTransaction transaction = transactions.findByTransactionId(transactionId)
                .orElseThrow(() -> new MlDetectionException("ML_INPUT_UNAVAILABLE"));
        if (!transaction.getOccurredAt().equals(cutoff)) {
            throw new MlDetectionException("ML_CUTOFF_MISMATCH");
        }
        List<BehaviorEvent> found = events.findForMlEvaluation(
                transaction.getExternalCustomerRef(), TYPES, cutoff.minus(Duration.ofHours(24)),
                cutoff, PageRequest.of(0, MAX_EVENTS + 1));
        if (found.size() > MAX_EVENTS) throw new MlDetectionException("ML_EVENT_LIMIT_EXCEEDED");
        List<MlEvent> safeEvents = found.stream().map(e -> new MlEvent(
                e.getEventId(), e.getEventType().name(), e.getOccurredAt(), e.getCreatedAt())).toList();
        MlRequest request = new MlRequest(transactionId, cutoff, transaction.getAmount().toPlainString(),
                transaction.getTransactionType().name(), transaction.getChannel().name(),
                MlDetectionPolicy.FEATURE_VERSION, scoringPolicyVersion, modelVersion,
                modelSha256, safeEvents);
        final MlResponse response;
        try {
            ResponseEntity<String> reply = client.post().uri("/api/v1/ml-inference")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().toEntity(String.class);
            List<String> contentTypes = reply.getHeaders().get(HttpHeaders.CONTENT_TYPE);
            if (reply.getStatusCode().value() != 200 || contentTypes == null
                    || contentTypes.size() != 1
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(
                            MediaType.parseMediaType(contentTypes.get(0)))
                    || reply.getBody() == null || reply.getBody().isBlank()) {
                throw new MlDetectionException("ML_INVALID_RESPONSE");
            }
            response = mapper.readValue(reply.getBody(), MlResponse.class);
        } catch (MlDetectionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new MlDetectionException(serviceFailureCode(exception));
        } catch (ResourceAccessException exception) {
            throw new MlDetectionException(hasTimeout(exception)
                    ? "ML_TIMEOUT" : "ML_SERVICE_UNAVAILABLE");
        } catch (RestClientException exception) {
            throw new MlDetectionException("ML_SERVICE_UNAVAILABLE");
        } catch (Exception exception) {
            throw new MlDetectionException("ML_INVALID_RESPONSE");
        }
        if (response == null || !transactionId.equals(response.transactionId())
                || !cutoff.equals(response.evaluationCutoffAt())
                || !MlDetectionPolicy.FEATURE_VERSION.equals(response.featureVersion())
                || !scoringPolicyVersion.equals(response.scoringPolicyVersion())
                || !modelVersion.equals(response.modelVersion())
                || !modelSha256.equals(response.modelSha256())
                || response.probabilityBasisPoints() < 0 || response.probabilityBasisPoints() > 10000
                || !(response.probabilityBasisPoints() > 5000 ? "ML_RISK_SIGNAL" : "ML_BELOW_THRESHOLD")
                    .equals(response.reasonCode())) {
            throw new MlDetectionException("ML_INVALID_RESPONSE");
        }
        return new MlResult(response.probabilityBasisPoints(), response.reasonCode());
    }

    private void recordInference(String category, long elapsedNanos) {
        if (meterRegistry == null) return;
        try {
            String outcome = "NONE".equals(category) ? "success" : "failure";
            Counter.builder("finguardops.ml.inference.total")
                    .tag("outcome", outcome).tag("category", category)
                    .register(meterRegistry).increment();
            Timer.builder("finguardops.ml.inference.duration")
                    .tag("outcome", outcome).tag("category", category)
                    .register(meterRegistry).record(Duration.ofNanos(Math.max(0, elapsedNanos)));
        } catch (Throwable ignored) {
            // Metrics cannot alter a transaction decision.
        }
    }

    private String serviceFailureCode(RestClientResponseException exception) {
        try {
            JsonNode detail = mapper.readTree(exception.getResponseBodyAsByteArray()).path("detail");
            String code = detail.path("code").asText();
            return switch (code) {
                case "MODEL_VERSION_MISMATCH" -> "ML_MODEL_VERSION_MISMATCH";
                case "MODEL_HASH_MISMATCH" -> "ML_MODEL_HASH_MISMATCH";
                case "MODEL_UNAVAILABLE", "MODEL_INVALID" -> "ML_MODEL_UNAVAILABLE";
                case "DUPLICATE_EVENT", "EVENT_OUTSIDE_CUTOFF", "INVALID_FEATURES",
                        "INVALID_AMOUNT", "INVALID_TRANSACTION_TYPE", "INVALID_CHANNEL",
                        "INVALID_CUTOFF" -> "ML_INVALID_FEATURES";
                default -> exception.getStatusCode().is4xxClientError()
                        ? "ML_INVALID_FEATURES" : "ML_SERVICE_UNAVAILABLE";
            };
        } catch (Exception ignored) {
            return exception.getStatusCode().is4xxClientError()
                    ? "ML_INVALID_FEATURES" : "ML_SERVICE_UNAVAILABLE";
        }
    }

    private boolean hasTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public record MlResult(int probabilityBasisPoints, String reasonCode) {
        public int contribution() { return MlDetectionPolicy.contribution(probabilityBasisPoints); }
    }
    public record MlEvent(UUID eventId, String eventType, Instant occurredAt, Instant createdAt) { }
    public record MlRequest(UUID transactionId, Instant evaluationCutoffAt, String amount,
                            String transactionType, String channel, String featureVersion,
                            String scoringPolicyVersion,
                            String modelVersion, String modelSha256, List<MlEvent> events) { }
    public record MlResponse(UUID transactionId, Instant evaluationCutoffAt, String featureVersion,
                             String scoringPolicyVersion, String modelVersion,
                             String modelSha256, int probabilityBasisPoints,
                             String reasonCode) { }
    public static final class MlDetectionException extends RuntimeException {
        private final String code;
        public MlDetectionException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
