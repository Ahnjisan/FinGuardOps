package com.aifds.backend.rule.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

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
        java.io.IOException first = new java.io.IOException("first");
        java.io.IOException second = new java.io.IOException("second", first);
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

    private ResourceAccessException failure(java.io.IOException cause) {
        return new ResourceAccessException("transport failed", cause);
    }
}
