package com.aifds.backend.externalrisk.client;

import com.aifds.backend.externalrisk.domain.ExternalRiskFailureCategory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ExternalRiskTransportErrorClassifier {

    private ExternalRiskTransportErrorClassifier() {
    }

    public static ExternalRiskFailureCategory classify(Throwable failure) {
        if (hasCause(failure, InterruptedException.class)
                || hasCause(failure, ClosedByInterruptException.class)) {
            Thread.currentThread().interrupt();
            return ExternalRiskFailureCategory.UNAVAILABLE;
        }
        if (hasCause(failure, HttpConnectTimeoutException.class)
                || hasCause(failure, HttpTimeoutException.class)
                || hasCause(failure, SocketTimeoutException.class)) {
            return ExternalRiskFailureCategory.TIMEOUT;
        }
        if (hasCause(failure, InterruptedIOException.class)) {
            Thread.currentThread().interrupt();
            return ExternalRiskFailureCategory.UNAVAILABLE;
        }
        if (hasCause(failure, UnknownHostException.class)
                || hasCause(failure, ConnectException.class)
                || hasCause(failure, SSLException.class)
                || hasCause(failure, IOException.class)) {
            return ExternalRiskFailureCategory.UNAVAILABLE;
        }
        return ExternalRiskFailureCategory.UNAVAILABLE;
    }

    private static boolean hasCause(
            Throwable failure,
            Class<? extends Throwable> type
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
