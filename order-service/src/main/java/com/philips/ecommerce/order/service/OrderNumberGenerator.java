package com.philips.ecommerce.order.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Generates human-friendly, non-guessable order numbers: {@code ORD-yyyyMMdd-XXXXXXXX} (8 upper-case hex chars,
 * 2^32 values per day). Not derived from the DB id so that order numbers do not leak business volume; the unique
 * constraint on order_number is the final guard against the (tiny) collision probability.
 */
@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OrderNumberGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        String date = LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(DATE);
        String suffix = String.format(Locale.ROOT, "%08X", random.nextInt());
        return "ORD-" + date + "-" + suffix;
    }
}
