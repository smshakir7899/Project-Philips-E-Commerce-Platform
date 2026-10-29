package com.philips.ecommerce.payment.outbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.outbox.*}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    /** Turns the scheduled publisher on/off (off in most tests, where Kafka is not available). */
    private boolean enabled = true;

    /** Delay between two polls, in ms (used by @Scheduled through a placeholder, kept here for documentation). */
    private long pollIntervalMs = 1000;

    /** Maximum rows sent per poll (bounds the lock duration of one publisher transaction). */
    private int batchSize = 50;

    /** After this many failed sends a row is parked as FAILED. */
    private int maxAttempts = 10;

    /** How long one synchronous Kafka send may take before it counts as a failure. */
    private long sendTimeoutMs = 10000;

    /** How the publisher selects PENDING rows. */
    private FetchMode fetchMode = FetchMode.SKIP_LOCKED;

    public enum FetchMode {
        /** {@code SELECT ... FOR UPDATE SKIP LOCKED}: safe with many instances (MySQL 8). */
        SKIP_LOCKED,
        /** Plain select without locks: single instance only (H2 tests). */
        SIMPLE
    }
}
