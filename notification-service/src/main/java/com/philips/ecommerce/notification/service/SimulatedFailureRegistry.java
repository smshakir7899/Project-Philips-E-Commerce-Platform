package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Customers whose notifications must fail - a switch to demonstrate retry + dead-letter topic + replay end to end.
 *
 * <p>Thread safety: read on every record by up to {@code concurrency} Kafka listener threads and written by HTTP
 * threads (PUT admin endpoint). The whole set is replaced atomically through an {@link AtomicReference} to an
 * immutable copy (copy-on-write). Readers never lock and never see a half-updated set, which a
 * {@code clear()} + {@code addAll()} on a shared concurrent set could expose.</p>
 */
@Component
public class SimulatedFailureRegistry {

    private static final Logger log = LoggerFactory.getLogger(SimulatedFailureRegistry.class);

    private final AtomicReference<Set<String>> failingCustomerIds = new AtomicReference<>(Collections.<String>emptySet());

    public SimulatedFailureRegistry(NotificationProperties properties) {
        replaceAll(properties.getSimulation().getFailingCustomerIds());
    }

    public boolean isFailing(String customerId) {
        return customerId != null && failingCustomerIds.get().contains(customerId);
    }

    /** Replaces the whole set; an empty collection disables the simulation. */
    public void replaceAll(Collection<String> customerIds) {
        Set<String> copy = new LinkedHashSet<>();
        if (customerIds != null) {
            for (String id : customerIds) {
                if (id != null && !id.trim().isEmpty()) {
                    copy.add(id.trim());
                }
            }
        }
        failingCustomerIds.set(Collections.unmodifiableSet(copy));
        log.info("Simulated notification failures now configured for customers {}", copy);
    }

    public List<String> getFailingCustomerIds() {
        return new ArrayList<>(failingCustomerIds.get());
    }
}
