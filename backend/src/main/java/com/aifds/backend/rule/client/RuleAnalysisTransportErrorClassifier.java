package com.aifds.backend.rule.client;

import com.fasterxml.jackson.core.JacksonException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class RuleAnalysisTransportErrorClassifier {

    private RuleAnalysisTransportErrorClassifier() {
    }

    static RuleAnalysisClientErrorCategory classify(
            ResourceAccessException exception
    ) {
        if (hasCause(exception, HttpConnectTimeoutException.class)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_CONNECT_TIMEOUT;
        }
        if (hasCause(exception, HttpTimeoutException.class)
                || hasCause(exception, SocketTimeoutException.class)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_RESPONSE_TIMEOUT;
        }
        if (hasCause(exception, ConnectException.class)
                || hasCause(exception, UnknownHostException.class)) {
            return RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE;
        }
        return RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE;
    }

    static Optional<RuleAnalysisClientErrorCategory> classifyBodyReadFailure(
            Throwable exception,
            BooleanSupplier responseTimeoutExpired
    ) {
        Objects.requireNonNull(exception, "exception must not be null");
        Objects.requireNonNull(
                responseTimeoutExpired,
                "responseTimeoutExpired must not be null"
        );
        if (!hasNonJacksonIOException(exception)) {
            return Optional.empty();
        }
        return Optional.of(responseTimeoutExpired.getAsBoolean()
                ? RuleAnalysisClientErrorCategory.AI_SERVICE_RESPONSE_TIMEOUT
                : RuleAnalysisClientErrorCategory.AI_SERVICE_UNAVAILABLE);
    }

    private static boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type
    ) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasNonJacksonIOException(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof IOException
                    && !(current instanceof JacksonException)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
