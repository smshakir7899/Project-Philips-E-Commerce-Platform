package com.philips.ecommerce.notification.client;

import com.philips.ecommerce.notification.client.dto.EmailMessage;
import com.philips.ecommerce.notification.config.NotificationProperties;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;
import com.philips.ecommerce.notification.service.SimulatedFailureRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingEmailSenderTest {

    @Test
    void simulatedFailureThrowsOnlyForConfiguredCustomers() {
        NotificationProperties properties = new NotificationProperties();
        properties.getSimulation().getFailingCustomerIds().add("CUST-FAIL");
        SimulatedFailureRegistry registry = new SimulatedFailureRegistry(properties);
        LoggingEmailSender sender = new LoggingEmailSender(registry);

        assertThatThrownBy(() -> sender.send(message("CUST-FAIL")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("CUST-FAIL");
        assertThatCode(() -> sender.send(message("CUST-OK"))).doesNotThrowAnyException();

        registry.replaceAll(Collections.<String>emptyList());
        assertThatCode(() -> sender.send(message("CUST-FAIL"))).doesNotThrowAnyException();
    }

    @Test
    void registryTrimsAndIgnoresBlankIds() {
        SimulatedFailureRegistry registry = new SimulatedFailureRegistry(new NotificationProperties());

        registry.replaceAll(Arrays.asList(" CUST-1 ", "", null, "CUST-2"));

        assertThat(registry.getFailingCustomerIds()).containsExactly("CUST-1", "CUST-2");
        assertThat(registry.isFailing("CUST-1")).isTrue();
        assertThat(registry.isFailing(null)).isFalse();
    }

    private EmailMessage message(String customerId) {
        return new EmailMessage("no-reply@x", customerId + "@example.com", "subject", "body", customerId);
    }
}
