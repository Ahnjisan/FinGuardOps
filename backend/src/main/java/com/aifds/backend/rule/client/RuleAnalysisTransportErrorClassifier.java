package com.aifds.backend.rule.client;

import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
}
