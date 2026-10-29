package com.philips.ecommerce.order.controller;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.web.GlobalExceptionHandler;
import com.philips.ecommerce.order.dto.CheckoutRequest;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.dto.OrderSummaryResponse;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.exception.PaymentDeclinedException;
import com.philips.ecommerce.order.service.CheckoutResult;
import com.philips.ecommerce.order.service.CheckoutService;
import com.philips.ecommerce.order.service.OrderQueryService;
import com.philips.ecommerce.order.service.OrderStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer only. common-lib's GlobalExceptionHandler is registered by an auto-configuration that @WebMvcTest does
 * not load, so it is imported explicitly to get the real error body.
 */
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    private static final String BODY = "{\"customerId\":\"CUST-1001\",\"cardToken\":\"tok_visa\","
            + "\"shippingAddress\":{\"fullName\":\"Jane Doe\",\"line1\":\"Main St 1\",\"city\":\"Amsterdam\","
            + "\"postalCode\":\"1011AA\",\"country\":\"NL\"}}";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CheckoutService checkoutService;
    @MockBean
    private OrderQueryService orderQueryService;
    @MockBean
    private OrderStateService orderStateService;

    private static OrderResponse order(OrderStatus status) {
        return OrderResponse.builder()
                .orderNumber("ORD-20260101-ABCDEF01")
                .customerId("CUST-1001")
                .status(status)
                .totalAmount(new BigDecimal("179.98"))
                .currency("EUR")
                .items(Collections.emptyList())
                .createdAt(Instant.parse("2026-01-01T10:00:00Z"))
                .build();
    }

    @Test
    void newCheckoutReturns201WithLocation() throws Exception {
        when(checkoutService.checkout(eq("idem-key-0001"), any(CheckoutRequest.class)))
                .thenReturn(CheckoutResult.created(order(OrderStatus.CONFIRMED)));

        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/ORD-20260101-ABCDEF01"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-20260101-ABCDEF01"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(179.98))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void replayReturns200WithHeader() throws Exception {
        when(checkoutService.checkout(eq("idem-key-0001"), any(CheckoutRequest.class)))
                .thenReturn(CheckoutResult.replayed(order(OrderStatus.CONFIRMED)));

        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-20260101-ABCDEF01"));
    }

    @Test
    void declinedPaymentReturns402WithOrderNumber() throws Exception {
        when(checkoutService.checkout(eq("idem-key-0002"), any(CheckoutRequest.class)))
                .thenThrow(new PaymentDeclinedException("Payment was declined: CARD_DECLINED")
                        .withDetail("orderNumber", "ORD-20260101-ABCDEF02"));

        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "idem-key-0002")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.details.orderNumber").value("ORD-20260101-ABCDEF02"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/orders/checkout").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void tooShortIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/orders/checkout").header("Idempotency-Key", "idem-key-0003")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"CUST-1001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verifyNoInteractions(checkoutService);
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        when(orderQueryService.getOrder("ORD-X"))
                .thenThrow(new ResourceNotFoundException("ORDER_NOT_FOUND", "Order ORD-X not found"));

        mvc.perform(get("/api/orders/ORD-X"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void historyIsPaged() throws Exception {
        OrderSummaryResponse summary = new OrderSummaryResponse("ORD-1", OrderStatus.CONFIRMED,
                new BigDecimal("10.00"), "EUR", 2, Instant.parse("2026-01-01T10:00:00Z"));
        when(orderQueryService.getHistory("CUST-1001", 0, 10)).thenReturn(
                new PageResponse<>(Collections.singletonList(summary), 0, 10, 1, 1, true));

        mvc.perform(get("/api/orders").param("customerId", "CUST-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pageSizeAbove100IsRejected() throws Exception {
        mvc.perform(get("/api/orders").param("customerId", "CUST-1001").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusUpdateDelegates() throws Exception {
        when(orderStateService.updateFulfilmentStatus("ORD-1", OrderStatus.SHIPPED))
                .thenReturn(order(OrderStatus.SHIPPED));

        mvc.perform(patch("/api/orders/ORD-1/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void customerCancelDelegates() throws Exception {
        when(orderStateService.cancelByCustomer("ORD-1")).thenReturn(order(OrderStatus.CANCELLED));

        mvc.perform(post("/api/orders/ORD-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
