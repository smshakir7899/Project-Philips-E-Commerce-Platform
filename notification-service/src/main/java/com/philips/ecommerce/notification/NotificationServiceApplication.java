package com.philips.ecommerce.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Notification service: listens to the order/payment topics and informs the customer (SIMULATED e-mail: the message
 * is logged and stored, nothing is really sent). It is also the platform's dead-letter collector: every consumer in
 * every service publishes records it cannot process to {@code <topic>.DLT}, and this service stores them as
 * {@code failed_event} rows that an operator can inspect and replay through the admin API.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
