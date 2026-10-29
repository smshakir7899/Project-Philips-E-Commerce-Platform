package com.philips.ecommerce.payment.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates references like {@code PAY-3FA94C0D12BE} (12 upper-case hex chars, 48 random bits). */
@Component
public class PaymentReferenceGenerator {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** SecureRandom: references are exposed to customers, so they must not be guessable/sequential. */
    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder sb = new StringBuilder("PAY-");
        for (int i = 0; i < 12; i++) {
            sb.append(HEX[random.nextInt(16)]);
        }
        return sb.toString();
    }
}
