package com.aifds.backend.security.support;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public final class InProcessJwkSetServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();

    private volatile int status = 200;
    private volatile String body = "{\"keys\":[]}";
    private volatile Duration delay = Duration.ZERO;

    private InProcessJwkSetServer(
            HttpServer server,
            ExecutorService executor
    ) {
        this.server = server;
        this.executor = executor;
    }

    public static InProcessJwkSetServer start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "test-jwk-server");
                thread.setDaemon(true);
                return thread;
            });
            InProcessJwkSetServer fixture = new InProcessJwkSetServer(
                    server,
                    executor
            );
            server.createContext("/jwks", fixture::handle);
            server.setExecutor(executor);
            server.start();
            return fixture;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not start in-process JWK server",
                    exception
            );
        }
    }

    public URI uri() {
        return URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/jwks"
        );
    }

    public void serveKeys(JWK... keys) {
        status = 200;
        delay = Duration.ZERO;
        body = JSONObjectUtils.toJSONString(
                new JWKSet(List.of(keys)).toJSONObject()
        );
    }

    public void serveFailure(int responseStatus, String responseBody) {
        status = responseStatus;
        delay = Duration.ZERO;
        body = responseBody;
    }

    public void serveDelayedKeys(Duration responseDelay, JWK... keys) {
        status = 200;
        delay = responseDelay;
        body = JSONObjectUtils.toJSONString(
                new JWKSet(List.of(keys)).toJSONObject()
        );
    }

    public int requestCount() {
        return requests.get();
    }

    public void resetRequestCount() {
        requests.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try {
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
