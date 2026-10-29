package com.philips.ecommerce.inventory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;

/** Business settings bound from {@code app.inventory.*} (config-repo/inventory-service.yml). */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.inventory")
public class InventoryProperties {

    /** Minutes after which an uncommitted reservation is returned to stock by the expiry job. */
    @Min(1)
    private long reservationTtlMinutes = 15;

    /** Turns the expiry job off (tests, or when a dedicated instance should run it). */
    private boolean expiryJobEnabled = true;

    /** Fixed delay between two expiry job runs. */
    @Min(1000)
    private long expiryJobIntervalMs = 60000;

    /** Max reservations expired per batch. */
    @Min(1)
    private int expiryBatchSize = 100;
}
