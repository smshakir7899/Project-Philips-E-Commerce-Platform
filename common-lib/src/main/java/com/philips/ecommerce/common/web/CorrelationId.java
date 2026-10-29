package com.philips.ecommerce.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/** Helper around the MDC key used in every log line ("%X{correlationId}") and the X-Correlation-Id header. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
