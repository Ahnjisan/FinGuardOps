package com.aifds.backend.externalrisk.client;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalRiskTransportErrorClassifierTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void classifiesEveryTimeoutFormAsTimeout() {
        assertThat(ExternalRiskTransportErrorClassifier.classify(
                new HttpConnectTimeoutException("sensitive connect message")
        )).isEqualTo(ExternalRiskFailureCategory.TIMEOUT);
        assertThat(ExternalRiskTransportErrorClassifier.classify(
                new HttpTimeoutException("sensitive response message")
        )).isEqualTo(ExternalRiskFailureCategory.TIMEOUT);
        assertThat(ExternalRiskTransportErrorClassifier.classify(
                new SocketTimeoutException("sensitive read message")
        )).isEqualTo(ExternalRiskFailureCategory.TIMEOUT);
    }

    @Test
    void classifiesDnsConnectionTlsAndIoAsUnavailable() {
        assertUnavailable(new UnknownHostException("secret.example"));
        assertUnavailable(new ConnectException("connection message"));
        assertUnavailable(new SSLException("certificate message"));
        assertUnavailable(new IOException("transport message"));
    }

    @Test
    void restoresTheInterruptFlagAndClassifiesUnavailable() {
        assertThat(ExternalRiskTransportErrorClassifier.classify(
                new RuntimeException(new InterruptedException("interrupted"))
        )).isEqualTo(ExternalRiskFailureCategory.UNAVAILABLE);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(ExternalRiskTransportErrorClassifier.classify(
                new InterruptedIOException("interrupted io")
        )).isEqualTo(ExternalRiskFailureCategory.UNAVAILABLE);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void handlesCauseCyclesWithoutLooping() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);
        assertUnavailable(first);
    }

    private void assertUnavailable(Throwable failure) {
        assertThat(ExternalRiskTransportErrorClassifier.classify(failure))
                .isEqualTo(ExternalRiskFailureCategory.UNAVAILABLE);
    }
}
