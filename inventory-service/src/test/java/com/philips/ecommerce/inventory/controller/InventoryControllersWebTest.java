package com.philips.ecommerce.inventory.controller;

import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.web.GlobalExceptionHandler;
import com.philips.ecommerce.inventory.dto.InventoryResponse;
import com.philips.ecommerce.inventory.dto.ReservationItemResponse;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.dto.ReservationResponse;
import com.philips.ecommerce.inventory.entity.ReservationStatus;
import com.philips.ecommerce.inventory.service.InventoryService;
import com.philips.ecommerce.inventory.service.ReservationResult;
import com.philips.ecommerce.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer only: request mapping, validation, status codes and the error body. common-lib's
 * GlobalExceptionHandler is imported explicitly because @WebMvcTest does not load our auto-configuration.
 */
@WebMvcTest(controllers = {InventoryController.class, ReservationController.class})
@Import(GlobalExceptionHandler.class)
class InventoryControllersWebTest {

    private static final String ORDER = "ORD-20240501-CCCC0001";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private InventoryService inventoryService;
    @MockBean
    private ReservationService reservationService;

    @Test
    void newReservationReturns201WithLocation() throws Exception {
        when(reservationService.reserve(any(ReservationRequest.class)))
                .thenReturn(ReservationResult.created(reservation()));

        mockMvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNumber\":\"" + ORDER + "\",\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/inventory/reservations/" + ORDER)))
                .andExpect(jsonPath("$.orderNumber").value(ORDER))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.expiresAt").value("2024-05-01T10:15:00Z"));
    }

    @Test
    void replayedReservationReturns200() throws Exception {
        when(reservationService.reserve(any(ReservationRequest.class)))
                .thenReturn(ReservationResult.replayed(reservation()));

        mockMvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNumber\":\"" + ORDER + "\",\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.orderNumber").value(ORDER));
    }

    @Test
    void insufficientStockReturns409WithDetails() throws Exception {
        when(reservationService.reserve(any(ReservationRequest.class))).thenThrow(
                new ConflictException("INSUFFICIENT_STOCK", "Insufficient stock for product 5")
                        .withDetail("productId", 5L).withDetail("requested", 3).withDetail("available", 2));

        mockMvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNumber\":\"" + ORDER + "\",\"items\":[{\"productId\":5,\"quantity\":3}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.path").value("/api/inventory/reservations"))
                .andExpect(jsonPath("$.details.productId").value(5))
                .andExpect(jsonPath("$.details.requested").value(3))
                .andExpect(jsonPath("$.details.available").value(2));
    }

    @Test
    void invalidReservationRequestReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNumber\":\" \",\"items\":[{\"productId\":null,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("orderNumber", "items[0].productId", "items[0].quantity")));
        verifyNoInteractions(reservationService);
    }

    @Test
    void emptyItemsAreRejected() throws Exception {
        mockMvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNumber\":\"" + ORDER + "\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items"));
    }

    @Test
    void releaseOfUnknownReservationReturns404() throws Exception {
        when(reservationService.release("ORD-404")).thenThrow(
                new ResourceNotFoundException("RESERVATION_NOT_FOUND", "No reservation for order ORD-404"));

        mockMvc.perform(post("/api/inventory/reservations/ORD-404/release"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void getInventoryReturnsStockWithInStockFlag() throws Exception {
        when(inventoryService.getByProductId(7L)).thenReturn(InventoryResponse.builder().productId(7L)
                .sku("PH-AIRF-HD9870").availableQuantity(0).reservedQuantity(0).lowStockThreshold(5)
                .inStock(false).updatedAt(Instant.parse("2024-01-01T09:00:00Z")).build());

        mockMvc.perform(get("/api/inventory/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("PH-AIRF-HD9870"))
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void unknownInventoryReturns404() throws Exception {
        when(inventoryService.getByProductId(42L)).thenThrow(
                new ResourceNotFoundException("INVENTORY_NOT_FOUND", "No inventory record for product 42"));

        mockMvc.perform(get("/api/inventory/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
    }

    @Test
    void availabilityQuantityMustBePositive() throws Exception {
        mockMvc.perform(get("/api/inventory/1/availability").param("quantity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(inventoryService);
    }

    @Test
    void invalidStockUpdateReturns400() throws Exception {
        mockMvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"availableQuantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("sku", "availableQuantity", "lowStockThreshold")));
    }

    @Test
    void stockUpdateReturns200() throws Exception {
        when(inventoryService.upsert(eq(1L), any())).thenReturn(InventoryResponse.builder().productId(1L)
                .sku("PH-SONIC-9900").availableQuantity(50).lowStockThreshold(5).inStock(true).build());

        mockMvc.perform(put("/api/inventory/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"PH-SONIC-9900\",\"availableQuantity\":50,\"lowStockThreshold\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(50));
    }

    private static ReservationResponse reservation() {
        return ReservationResponse.builder()
                .orderNumber(ORDER)
                .status(ReservationStatus.RESERVED)
                .items(Collections.singletonList(new ReservationItemResponse(1L, 2)))
                .createdAt(Instant.parse("2024-05-01T10:00:00Z"))
                .expiresAt(Instant.parse("2024-05-01T10:15:00Z"))
                .build();
    }
}
