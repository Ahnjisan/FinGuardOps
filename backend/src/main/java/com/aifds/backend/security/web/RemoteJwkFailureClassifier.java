package com.aifds.backend.security.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.proc.BadJOSEException;
import javax.net.ssl.SSLException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.web.client.HttpServerErrorException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

public final class RemoteJwkFailureClassifier {

    public Classification classify(Throwable failure) {
        List<Throwable> causes = causeChain(failure);
        int remoteIndex = indexOf(causes, RemoteKeySourceException.class);
        if (remoteIndex >= 0) {
            List<Throwable> remoteCauses = causes.subList(
                    remoteIndex,
                    causes.size()
            );
            if (remoteCauses.stream().anyMatch(this::isTimeout)) {
                return Classification.DEPENDENCY_TIMEOUT;
            }
            if (remoteCauses.stream().anyMatch(this::isUnavailable)) {
                return Classification.DEPENDENCY_UNAVAILABLE;
            }
        }

        if (causes.stream().anyMatch(cause ->
                cause.getClass().equals(
                        org.springframework.security.oauth2.jwt.JwtException.class
                ))) {
            return Classification.INTERNAL_ERROR;
        }

        if (causes.stream().anyMatch(this::isKnownInvalidTokenFailure)) {
            return Classification.UNAUTHORIZED;
        }
        return Classification.INTERNAL_ERROR;
    }

    private List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Throwable current = failure;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private int indexOf(
            List<Throwable> causes,
            Class<? extends Throwable> type
    ) {
        for (int index = 0; index < causes.size(); index++) {
            if (type.isInstance(causes.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isTimeout(Throwable failure) {
        return failure instanceof SocketTimeoutException
                || failure instanceof HttpTimeoutException
                || failure.getClass().getName().equals(
                "org.apache.hc.client5.http.ConnectTimeoutException"
        );
    }

    private boolean isUnavailable(Throwable failure) {
        return failure instanceof ConnectException
                || failure instanceof UnknownHostException
                || failure instanceof NoRouteToHostException
                || failure instanceof PortUnreachableException
                || failure instanceof SSLException
                || failure instanceof HttpServerErrorException;
    }

    private boolean isKnownInvalidTokenFailure(Throwable failure) {
        return failure instanceof BadJwtException
                || failure instanceof JwtValidationException
                || failure instanceof BadJOSEException
                || failure instanceof JOSEException;
    }

    public enum Classification {
        UNAUTHORIZED,
        DEPENDENCY_TIMEOUT,
        DEPENDENCY_UNAVAILABLE,
        INTERNAL_ERROR
    }
}
