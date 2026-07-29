package com.aifds.backend.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {

    private static final String UUID_V4_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void removeTraceIdFromTestThread() {
        MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
    }

    @Test
    void generatesCanonicalLowercaseUuidV4WhenHeaderIsMissing()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = execute(request);

        assertCanonicalUuidV4(
                response.getHeader(TraceIdFilter.TRACE_ID_HEADER)
        );
    }

    @Test
    void preservesValidExternalTraceIdWithoutNormalization()
            throws Exception {
        String externalTraceId = "Client.Trace_ID:Ab-01";
        MockHttpServletRequest request = requestWithTraceId(externalTraceId);

        MockHttpServletResponse response = execute(request);

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(externalTraceId);
    }

    @Test
    void acceptsEightAndSixtyFourCharacterBoundaries() throws Exception {
        String minimum = "A1234567";
        String maximum = "A" + "b".repeat(63);

        assertThat(execute(requestWithTraceId(minimum))
                .getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(minimum);
        assertThat(execute(requestWithTraceId(maximum))
                .getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(maximum);
    }

    @Test
    void replacesSevenAndSixtyFiveCharacterValues() throws Exception {
        assertGeneratedReplacement("A123456");
        assertGeneratedReplacement("A" + "b".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".abcdefg",
            "_abcdefg",
            ":abcdefg",
            "-abcdefg"
    })
    void replacesValueWithInvalidFirstCharacter(String invalidTraceId)
            throws Exception {
        assertGeneratedReplacement(invalidTraceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "trace id",
            "trace\tid",
            "trace한글id",
            "trace\u0001id",
            "trace/id"
    })
    void replacesBlankWhitespaceNonAsciiControlAndUnsupportedCharacters(
            String invalidTraceId
    ) throws Exception {
        assertGeneratedReplacement(invalidTraceId);
    }

    @Test
    void replacesMultipleHeaderLinesUsingTheActualHeaderEnumeration()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace_first_01");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace_second_02");

        MockHttpServletResponse response = execute(request);

        String actual = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertCanonicalUuidV4(actual);
        assertThat(actual)
                .isNotEqualTo("trace_first_01")
                .isNotEqualTo("trace_second_02");
    }

    @Test
    void replacesCommaCombinedHeaderValue() throws Exception {
        assertGeneratedReplacement("trace_first_01,trace_second_02");
    }

    @Test
    void usesOneTraceIdForAttributeMdcAndResponseDuringFilterChain()
            throws Exception {
        String externalTraceId = "trace_shared_01";
        MockHttpServletRequest request = requestWithTraceId(externalTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> attributeInChain = new AtomicReference<>();
        AtomicReference<String> mdcInChain = new AtomicReference<>();
        AtomicReference<String> headerInChain = new AtomicReference<>();

        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
            attributeInChain.set(chainRequest.getAttribute(
                    TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE
            ));
            mdcInChain.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
            headerInChain.set(((MockHttpServletResponse) chainResponse)
                    .getHeader(TraceIdFilter.TRACE_ID_HEADER));
        });

        assertThat(attributeInChain.get()).isEqualTo(externalTraceId);
        assertThat(mdcInChain.get()).isEqualTo(externalTraceId);
        assertThat(headerInChain.get()).isEqualTo(externalTraceId);
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(externalTraceId);
    }

    @Test
    void removesMdcAfterNormalCompletion() throws Exception {
        execute(requestWithTraceId("trace_cleanup_01"));

        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void removesMdcWhenFilterChainThrows() {
        MockHttpServletRequest request =
                requestWithTraceId("trace_exception_01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (chainRequest, chainResponse) -> {
            throw new ServletException("expected test failure");
        };

        assertThatThrownBy(() ->
                filter.doFilter(request, response, failingChain)
        ).isInstanceOf(ServletException.class)
                .hasMessage("expected test failure");
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void consecutiveHeaderlessRequestsUseDifferentUuids() throws Exception {
        String first = execute(new MockHttpServletRequest())
                .getHeader(TraceIdFilter.TRACE_ID_HEADER);
        String second = execute(new MockHttpServletRequest())
                .getHeader(TraceIdFilter.TRACE_ID_HEADER);

        assertCanonicalUuidV4(first);
        assertCanonicalUuidV4(second);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void concurrentRequestsKeepMdcValuesIsolated() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothChainsEntered = new CountDownLatch(2);
        CountDownLatch releaseChains = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> executeConcurrent(
                    "trace_concurrent_A",
                    bothChainsEntered,
                    releaseChains
            ));
            Future<String> second = executor.submit(() -> executeConcurrent(
                    "trace_concurrent_B",
                    bothChainsEntered,
                    releaseChains
            ));

            assertThat(bothChainsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseChains.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo("trace_concurrent_A");
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isEqualTo("trace_concurrent_B");
        } finally {
            releaseChains.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidExternalValueIsNotExposedInResponseOrRequestAttribute()
            throws Exception {
        String invalidTraceId = "injected trace value";
        MockHttpServletRequest request = requestWithTraceId(invalidTraceId);

        MockHttpServletResponse response = execute(request);

        String generated = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertCanonicalUuidV4(generated);
        assertThat(generated).doesNotContain(invalidTraceId);
        assertThat(request.getAttribute(
                TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE
        )).isEqualTo(generated);
    }

    private String executeConcurrent(
            String traceId,
            CountDownLatch bothChainsEntered,
            CountDownLatch releaseChains
    ) throws Exception {
        MockHttpServletRequest request = requestWithTraceId(traceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
            traceIdInChain.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
            bothChainsEntered.countDown();
            try {
                if (!releaseChains.await(5, TimeUnit.SECONDS)) {
                    throw new ServletException(
                            "timed out waiting to release filter chains"
                    );
                }
                assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY))
                        .isEqualTo(traceId);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServletException(exception);
            }
        });

        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(traceId);
        return traceIdInChain.get();
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
        });
        return response;
    }

    private MockHttpServletRequest requestWithTraceId(String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
        return request;
    }

    private void assertGeneratedReplacement(String invalidTraceId)
            throws Exception {
        MockHttpServletResponse response =
                execute(requestWithTraceId(invalidTraceId));

        String actual = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertCanonicalUuidV4(actual);
        assertThat(actual).isNotEqualTo(invalidTraceId);
    }

    private void assertCanonicalUuidV4(String traceId) {
        assertThat(traceId).isNotNull().matches(UUID_V4_PATTERN);
        UUID uuid = UUID.fromString(traceId);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.toString()).isEqualTo(traceId);
    }
}
