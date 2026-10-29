package com.philips.ecommerce.order.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNumberGeneratorTest {

    private final OrderNumberGenerator generator =
            new OrderNumberGenerator(Clock.fixed(Instant.parse("2026-03-15T23:30:00Z"), ZoneOffset.UTC));

    @Test
    void formatIsOrdDateHex() {
        assertThat(generator.next()).matches("ORD-20260315-[0-9A-F]{8}");
    }

    @Test
    void numbersAreRandom() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            numbers.add(generator.next());
        }
        assertThat(numbers).hasSizeGreaterThan(995);
    }
}
