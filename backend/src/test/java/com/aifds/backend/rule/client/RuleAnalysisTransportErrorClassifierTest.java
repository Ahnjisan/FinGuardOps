package com.aifds.backend.rule.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RuleAnalysisTransportErrorClassifierTest {

    @Test
    void classifiesConnectTimeoutBeforeTheParentHttpTimeoutType() {
        ResourceAccessException failure = failure(
                new HttpConnectTimeoutException("connect timeout")
        );

        assertThat(RuleAnalysisTransportErrorClassifier.classify(failure))
                .isEqualTo(
                        RuleAnalysisClientErrorCategory.AI_SERVICE_CONNECT_TIMEOUT
                );
    }

    @Test
    void classifiesResponseAndReadTimeouts() {
        assertThat(RuleAnalysisTransportErrorClassifier.classify(
                failure(new HttpTimeoutException("response timeout"))
        )).isEqualTo(
                RuleAnalysisClientErrorCategory.AI_SERVICE_RESPONSE_TIMEOUT
        );
        assertThat(RuleAnalysisTransportErrorClassifier.classify(
                failure(new SocketTimeoutException("read timeout"))
        )).isEqualTo(
                RuleAnalysisClientErrorCategory.AI_SERVICE_RESPONSE_TIMEOUT
        );
    }

    @Test
    void classifiesConnectionRefusalDnsAndOtherTransportFailuresAsUnavailable() {
        assertThat(RuleAnalysisTransportErrorClassifier.classify(
                failure(new ConnectException("refused"))
        )).isEqualTo(RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE);
        assertThat(RuleAnalysisTransportErrorClassifier.classify(
                failure(new UnknownHostException("unresolved"))
        )).isEqualTo(RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE);
        assertThat(RuleAnalysisTransportErrorClassifier.classify(
                failure(new java.io.IOException("other transport failure"))
        )).isEqualTo(RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE);
    }

    @Test
    void stopsCauseTraversalWhenTheCauseChainContainsACycle() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);
        ResourceAccessException failure = new ResourceAccessException(
                "transport failed",
                first
        );

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThat(RuleAnalysisTransportErrorClassifier.classify(failure))
                        .isEqualTo(
                                RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE
                        ));
    }

    @Test
    void doesNotClassifyJacksonOnlyFailuresAsBodyTransportErrors() {
        AtomicBoolean timeoutChecked = new AtomicBoolean();
        JsonMappingException jacksonFailure = JsonMappingException.from(
                (JsonParser) null,
                "pure jackson failure"
        );

        Optional<RuleAnalysisClientErrorCategory> actual =
                RuleAnalysisTransportErrorClassifier.classifyBodyReadFailure(
                        jacksonFailure,
                        () -> {
                            timeoutChecked.set(true);
                            return true;
                        }
                );

        assertThat(actual).isEmpty();
        assertThat(timeoutChecked).isFalse();
    }

    @Test
    void classifiesWrappedBodyTransportIoWithoutReadingExceptionMessages() {
        JsonMappingException first = JsonMappingException.from(
                (JsonParser) null,
                "same jackson wrapper",
                new IOException("transport message one")
        );
        JsonMappingException second = JsonMappingException.from(
                (JsonParser) null,
                "same jackson wrapper",
                new IOException("completely different transport message")
        );

        assertThat(RuleAnalysisTransportErrorClassifier
                .classifyBodyReadFailure(first, () -> false))
                .contains(RuleAnalysisClientErrorCategory
                        .AI_SERVICE_UNAVAILABLE);
        assertThat(RuleAnalysisTransportErrorClassifier
                .classifyBodyReadFailure(second, () -> false))
                .contains(RuleAnalysisClientErrorCategory
                        .AI_SERVICE_UNAVAILABLE);
        assertThat(RuleAnalysisTransportErrorClassifier
                .classifyBodyReadFailure(first, () -> true))
                .contains(RuleAnalysisClientErrorCategory
                        .AI_SERVICE_RESPONSE_TIMEOUT);
        assertThat(RuleAnalysisTransportErrorClassifier
                .classifyBodyReadFailure(second, () -> true))
                .contains(RuleAnalysisClientErrorCategory
                        .AI_SERVICE_RESPONSE_TIMEOUT);
    }

    @Test
    void classifiesTopLevelNonJacksonIoAsBodyTransportError() {
        assertThat(RuleAnalysisTransportErrorClassifier
                .classifyBodyReadFailure(
                        new IOException("top-level transport failure"),
                        () -> false
                ))
                .contains(RuleAnalysisClientErrorCategory
                        .AI_SERVICE_UNAVAILABLE);
    }

    @Test
    void stopsBodyReadCauseTraversalWhenTheCauseChainContainsACycle() {
        RuntimeException first = new RuntimeException("cycle one");
        RuntimeException second = new RuntimeException("cycle two", first);
        first.initCause(second);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThat(RuleAnalysisTransportErrorClassifier
                        .classifyBodyReadFailure(first, () -> true))
                        .isEmpty());
    }

    private ResourceAccessException failure(IOException cause) {
        return new ResourceAccessException("transport failed", cause);
    }
}
