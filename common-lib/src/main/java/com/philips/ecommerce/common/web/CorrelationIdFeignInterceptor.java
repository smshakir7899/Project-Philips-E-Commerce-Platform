package com.philips.ecommerce.common.web;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/** Propagates the current correlation id on every outgoing OpenFeign call. */
public class CorrelationIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            template.header(CorrelationId.HEADER, correlationId);
        }
    }
}
