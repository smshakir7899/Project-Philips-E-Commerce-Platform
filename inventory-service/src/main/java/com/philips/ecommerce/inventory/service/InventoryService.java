package com.philips.ecommerce.inventory.service;

import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.inventory.dto.AvailabilityResponse;
import com.philips.ecommerce.inventory.dto.InventoryResponse;
import com.philips.ecommerce.inventory.dto.StockUpdateRequest;
import com.philips.ecommerce.inventory.entity.Inventory;
import com.philips.ecommerce.inventory.mapper.InventoryMapper;
import com.philips.ecommerce.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Stock queries and the admin stock upsert. Reservations are handled by {@link ReservationService}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    static final int MAX_BATCH_IDS = 100;

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public InventoryResponse getByProductId(Long productId) {
        return InventoryMapper.toResponse(findOrThrow(productId));
    }

    /** Unknown ids are simply absent from the result (same convention as the product batch endpoint). */
    @Transactional(readOnly = true)
    public List<InventoryResponse> getByProductIds(List<Long> productIds) {
        Set<Long> distinctIds = new LinkedHashSet<>(productIds);
        if (distinctIds.size() > MAX_BATCH_IDS) {
            throw new BadRequestException("TOO_MANY_PRODUCT_IDS",
                    "At most " + MAX_BATCH_IDS + " product ids can be requested at once");
        }
        List<InventoryResponse> result = new ArrayList<>();
        for (Inventory inventory : inventoryRepository.findAllByProductIdInOrderByProductIdAsc(distinctIds)) {
            result.add(InventoryMapper.toResponse(inventory));
        }
        return result;
    }

    /**
     * Advisory check without locking: the answer may be outdated a millisecond later. That is fine for the cart;
     * the authoritative, locked check is the reservation at checkout.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(Long productId, int quantity) {
        Inventory inventory = findOrThrow(productId);
        return AvailabilityResponse.builder()
                .productId(productId)
                .requestedQuantity(quantity)
                .availableQuantity(inventory.getAvailableQuantity())
                .available(inventory.canReserve(quantity))
                .build();
    }

    /**
     * Creates or updates the stock row of a product (admin / warehouse integration).
     *
     * <p>Why OPTIMISTIC locking here (the {@code @Version} column) but PESSIMISTIC locking for reservations:
     * admin updates are rare and conflicts unlikely, so we do not want to hold row locks; and Hibernate writes ALL
     * columns on update, so without a version check this read-modify-write could silently overwrite a
     * reservedQuantity changed by a concurrent reservation (lost update). With {@code @Version} the UPDATE carries
     * "WHERE version = ?", affects 0 rows in that case, and the caller gets 409 CONCURRENT_MODIFICATION and can
     * retry. Reservations, in contrast, are frequent and contended (hot products during a sale): failing and
     * retrying them optimistically would waste work and hurt the user, so they queue on a row lock instead.
     */
    @Transactional
    public InventoryResponse upsert(Long productId, StockUpdateRequest request) {
        String sku = request.getSku().trim();
        Optional<Inventory> sameSku = inventoryRepository.findBySku(sku);
        if (sameSku.isPresent() && !sameSku.get().getProductId().equals(productId)) {
            throw new ConflictException("DUPLICATE_SKU", "SKU " + sku + " already belongs to product "
                    + sameSku.get().getProductId());
        }

        Optional<Inventory> existing = inventoryRepository.findByProductId(productId);
        Inventory inventory;
        if (existing.isPresent()) {
            inventory = existing.get();
            inventory.setSku(sku);
            inventory.setAvailableQuantity(request.getAvailableQuantity());
            inventory.setLowStockThreshold(request.getLowStockThreshold());
            log.info("Updated stock of product {}: available={}, threshold={}", productId,
                    request.getAvailableQuantity(), request.getLowStockThreshold());
        } else {
            inventory = new Inventory(productId, sku, request.getAvailableQuantity(), request.getLowStockThreshold());
            log.info("Created stock record for product {} ({}) with {} units", productId, sku,
                    request.getAvailableQuantity());
        }
        // Flush now so a version conflict or constraint violation surfaces here and the response shows the
        // final updatedAt/version, instead of failing later at commit time.
        return InventoryMapper.toResponse(inventoryRepository.saveAndFlush(inventory));
    }

    private Inventory findOrThrow(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> InventoryErrors.inventoryNotFound(productId));
    }
}
