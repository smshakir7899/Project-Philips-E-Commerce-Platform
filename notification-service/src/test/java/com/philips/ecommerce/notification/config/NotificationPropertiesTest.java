package com.philips.ecommerce.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * config-repo/notification-service.yml sets failing-customer-ids to "${NOTIFICATION_FAILING_CUSTOMER_IDS:}", i.e. an
 * empty string when the variable is unset, or a comma-separated list. Both must bind to a Set.
 */
class NotificationPropertiesTest {

    @Test
    void emptyStringBindsToEmptySet() {
        NotificationProperties properties = bind("");

        assertThat(properties.getSimulation().getFailingCustomerIds()).isEmpty();
    }

    @Test
    void commaSeparatedValueBindsToSet() {
        NotificationProperties properties = bind("CUST-FAIL,CUST-2");

        assertThat(properties.getSimulation().getFailingCustomerIds()).containsExactly("CUST-FAIL", "CUST-2");
        assertThat(properties.getRecipientDomain()).isEqualTo("example.com");
        assertThat(properties.getDltGroupId()).isEqualTo("notification-service-dlt");
    }

    private NotificationProperties bind(String failingIds) {
        Map<String, Object> source = new HashMap<>();
        source.put("app.notification.simulation.failing-customer-ids", failingIds);
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("app.notification", NotificationProperties.class);
    }
}
