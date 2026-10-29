package com.philips.ecommerce.notification.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.util.LinkedHashSet;
import java.util.Set;

/** Binds {@code app.notification.*} (config-repo/notification-service.yml). */
@Data
@Validated
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    /** Simulated recipients are {@code <customerId>@<recipient-domain>} (there is no customer service). */
    @NotBlank
    private String recipientDomain = "example.com";

    /** "From" address printed by the simulated e-mail sender. */
    @NotBlank
    private String senderAddress = "no-reply@philips-shop.example";

    /** Group id of the dead-letter listener (must differ from the main group, see KafkaConfig). */
    @NotBlank
    private String dltGroupId = "notification-service-dlt";

    /** Upper bound for GET /admin/failed-events so an operator query can never load the whole table. */
    @Min(1)
    @Max(1000)
    private int failedEventsMaxResults = 200;

    @Valid
    private Simulation simulation = new Simulation();

    @Data
    public static class Simulation {
        /** Initial customers whose notifications fail (demo of retry + DLT); changeable at runtime via the API. */
        private Set<String> failingCustomerIds = new LinkedHashSet<>();
    }
}
