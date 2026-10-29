package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.inventory.config.InventoryProperties;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Saga safety net (CONTRACTS.md section 7, step 9): returns stock of reservations that were neither committed nor
 * released within the TTL - e.g. order-service crashed between reserving and paying.
 *
 * <p>Design:
 * <ul>
 *   <li>fixedDelay (not fixedRate): the next run starts only after the previous one finished, runs never overlap
 *       within one instance.</li>
 *   <li>Batches of ids, each reservation expired in its OWN transaction: short lock times, and one broken
 *       reservation does not roll back the others.</li>
 *   <li>Safe with several service instances: every reservation is locked and re-checked before it is expired, so
 *       two instances can never release the same stock twice (ShedLock would avoid the duplicate work as well).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.inventory", name = "expiry-job-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private final StockReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final InventoryProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.inventory.expiry-job-interval-ms:60000}",
            initialDelayString = "${app.inventory.expiry-job-interval-ms:60000}")
    public void run() {
        int expired = expireOverdueReservations();
        if (expired > 0) {
            log.info("Expiry job released {} overdue reservation(s)", expired);
        }
    }

    /** @return number of reservations expired in this run */
    public int expireOverdueReservations() {
        int batchSize = properties.getExpiryBatchSize();
        int total = 0;
        while (true) {
            Instant now = clock.instant();
            List<Long> ids = reservationRepository.findIdsByStatusAndExpiresAtBefore(
                    ReservationStatus.RESERVED, now, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                break;
            }
            int expiredInBatch = 0;
            for (Long id : ids) {
                try {
                    if (reservationService.expireReservation(id)) {
                        expiredInBatch++;
                    }
                } catch (RuntimeException e) {
                    // Keep going with the other reservations; this one is retried on the next run.
                    log.error("Could not expire reservation id={}", id, e);
                }
            }
            total += expiredInBatch;
            // Always query page 0: expired rows drop out of the result. Stop on a partial page, or when nothing
            // could be expired (only failures / concurrently handled rows) to avoid spinning on the same ids.
            if (ids.size() < batchSize || expiredInBatch == 0) {
                break;
            }
        }
        return total;
    }
}
