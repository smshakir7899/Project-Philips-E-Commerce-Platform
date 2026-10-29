package com.philips.ecommerce.product.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Schedules cache invalidation for AFTER the surrounding transaction commits.
 * <p>
 * Why not evict immediately inside the write transaction? Between "evict" and "commit" another request can
 * miss the cache, read the OLD committed row from MySQL (our change is not visible yet - READ COMMITTED /
 * REPEATABLE READ isolation) and put it back into Redis. After our commit the cache then holds stale data
 * until the TTL expires. Evicting after commit closes that window (only the much narrower classic cache-aside
 * race remains). If the transaction rolls back, nothing is evicted - nothing changed.
 * <p>
 * Implemented with {@link TransactionSynchronizationManager} (equivalent to publishing an event consumed by a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, but more explicit and trivially unit-testable).
 */
@Slf4j
@Component
public class ProductCacheEvictor {

    private final ProductCacheService cacheService;

    public ProductCacheEvictor(ProductCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public void evictAfterCommit(final Long productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cacheService.evictProduct(productId);
                }
            });
        } else {
            // Not inside a transaction (should not happen for writes): evict right away.
            log.debug("No active transaction, evicting product {} immediately", productId);
            cacheService.evictProduct(productId);
        }
    }
}
