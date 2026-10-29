package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.inventory.config.InventoryProperties;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryJobTest {

    private static final Instant NOW = Instant.parse("2024-05-01T10:00:00Z");

    @Mock
    private StockReservationRepository reservationRepository;
    @Mock
    private ReservationService reservationService;

    private ReservationExpiryJob job;

    @BeforeEach
    void setUp() {
        InventoryProperties properties = new InventoryProperties();
        properties.setExpiryBatchSize(2);
        job = new ReservationExpiryJob(reservationRepository, reservationService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void processesFullBatchesUntilAPartialBatch() {
        when(reservationRepository.findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), eq(NOW),
                eq(PageRequest.of(0, 2))))
                .thenReturn(Arrays.asList(1L, 2L), Collections.singletonList(3L));
        when(reservationService.expireReservation(1L)).thenReturn(true);
        when(reservationService.expireReservation(2L)).thenReturn(true);
        when(reservationService.expireReservation(3L)).thenReturn(true);

        assertThat(job.expireOverdueReservations()).isEqualTo(3);
        verify(reservationRepository, times(2)).findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED),
                eq(NOW), eq(PageRequest.of(0, 2)));
    }

    @Test
    void oneFailingReservationDoesNotStopTheOthersAndDoesNotLoopForever() {
        when(reservationRepository.findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), eq(NOW),
                eq(PageRequest.of(0, 2))))
                .thenReturn(Arrays.asList(1L, 2L), Arrays.asList(1L, 4L), Collections.<Long>emptyList());
        when(reservationService.expireReservation(1L)).thenThrow(new IllegalStateException("broken row"));
        when(reservationService.expireReservation(2L)).thenReturn(true);
        when(reservationService.expireReservation(4L)).thenReturn(true);

        assertThat(job.expireOverdueReservations()).isEqualTo(2);
    }

    @Test
    void stopsWhenNothingInABatchCouldBeExpired() {
        when(reservationRepository.findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), eq(NOW),
                eq(PageRequest.of(0, 2))))
                .thenReturn(Arrays.asList(1L, 2L));
        when(reservationService.expireReservation(1L)).thenReturn(false);
        when(reservationService.expireReservation(2L)).thenReturn(false);

        assertThat(job.expireOverdueReservations()).isZero();
        verify(reservationRepository, times(1)).findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED),
                eq(NOW), eq(PageRequest.of(0, 2)));
    }

    @Test
    void doesNothingWhenNoReservationIsOverdue() {
        when(reservationRepository.findIdsByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), eq(NOW),
                eq(PageRequest.of(0, 2))))
                .thenReturn(Collections.<Long>emptyList());

        assertThat(job.expireOverdueReservations()).isZero();
    }
}
