package com.philips.ecommerce.notification.client;

import com.philips.ecommerce.notification.client.dto.EmailMessage;
import com.philips.ecommerce.notification.exception.NotificationDeliveryException;
import com.philips.ecommerce.notification.service.SimulatedFailureRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SIMULATED e-mail sender: nothing leaves the machine, the e-mail is written to the log (and the caller stores it in
 * the {@code notification} table, visible through the REST API).
 *
 * <p>To demonstrate retries and the dead-letter topic, it throws {@link NotificationDeliveryException} for every
 * customer listed in {@link SimulatedFailureRegistry} - as a real provider would on an outage or a throttling
 * response.</p>
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final SimulatedFailureRegistry failureRegistry;

    public LoggingEmailSender(SimulatedFailureRegistry failureRegistry) {
        this.failureRegistry = failureRegistry;
    }

    @Override
    public void send(EmailMessage message) {
        if (failureRegistry.isFailing(message.getCustomerId())) {
            log.warn("[SIMULATED EMAIL] delivery to {} failed (customer {} is configured to fail)",
                    message.getTo(), message.getCustomerId());
            throw new NotificationDeliveryException("Simulated e-mail provider failure for customer "
                    + message.getCustomerId());
        }
        log.info("[SIMULATED EMAIL] from={} to={} subject=\"{}\"\n{}",
                message.getFrom(), message.getTo(), message.getSubject(), message.getBody());
    }
}
