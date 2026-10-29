package com.philips.ecommerce.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-service: records payment attempts for orders using a SIMULATED payment gateway.
 * <p>
 * IMPORTANT: there is NO integration with a real payment provider (Stripe, Adyen, ...). Card tokens such as
 * {@code tok_visa} or {@code tok_declined} drive deterministic outcomes, see
 * {@link com.philips.ecommerce.payment.client.SimulatedPaymentGateway}.
 * <p>
 * Feature switches (@EnableScheduling, @EnableConfigurationProperties ...) live in separate @Configuration classes
 * in the {@code config} package and not here: annotations on the @SpringBootApplication class are applied to every
 * slice test (@WebMvcTest, @DataJpaTest) too, where they would drag in unrelated infrastructure.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
