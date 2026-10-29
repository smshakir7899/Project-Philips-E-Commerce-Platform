package com.philips.ecommerce.order.client;

import com.philips.ecommerce.common.feign.RemoteServiceException;

import java.util.function.Predicate;

/**
 * Resilience4j {@code record-failure-predicate} for every circuit breaker of this service (configured in
 * config-repo/order-service.yml). Decides whether an exception says "the downstream service is unhealthy".
 * <p>
 * A business rejection (4xx, e.g. 409 INSUFFICIENT_STOCK or 400 validation) proves the service is up and answering
 * correctly - counting it would let a burst of "out of stock" answers open the circuit and block all checkouts.
 * Timeouts, connection errors and 5xx are real failures.
 * <p>
 * Needs a public no-arg constructor: Resilience4j instantiates it by class name.
 */
public class RemoteCallFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof RemoteServiceException) {
            return !((RemoteServiceException) throwable).isClientError();
        }
        return true;
    }
}
