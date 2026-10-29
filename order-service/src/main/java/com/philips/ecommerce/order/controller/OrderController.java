package com.philips.ecommerce.order.controller;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.order.dto.CheckoutRequest;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.dto.OrderSummaryResponse;
import com.philips.ecommerce.order.dto.OrderTrackingResponse;
import com.philips.ecommerce.order.dto.StatusUpdateRequest;
import com.philips.ecommerce.order.service.CheckoutResult;
import com.philips.ecommerce.order.service.CheckoutService;
import com.philips.ecommerce.order.service.OrderQueryService;
import com.philips.ecommerce.order.service.OrderStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.net.URI;

/**
 * REST API of order-service. {@code @Validated} enables method-level validation of simple parameters (header,
 * query params); violations become 400 VALIDATION_FAILED through common-lib's GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/orders")
@Validated
@Tag(name = "Orders", description = "Checkout, order history, tracking and fulfilment status")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final CheckoutService checkoutService;
    private final OrderQueryService orderQueryService;
    private final OrderStateService orderStateService;

    public OrderController(CheckoutService checkoutService, OrderQueryService orderQueryService,
                           OrderStateService orderStateService) {
        this.checkoutService = checkoutService;
        this.orderQueryService = orderQueryService;
        this.orderStateService = orderStateService;
    }

    /**
     * 201 + Location for a new order; 200 + {@code Idempotent-Replayed: true} when the key was seen before, so the
     * client can tell a replay from a fresh order (Stripe-style idempotency).
     */
    @PostMapping("/checkout")
    @Operation(summary = "Checkout the customer's cart (saga: reserve stock, pay, confirm)")
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        CheckoutResult result = checkoutService.checkout(idempotencyKey, request);
        if (result.isReplayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED, "true").body(result.getOrder());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/orders/{orderNumber}")
                .buildAndExpand(result.getOrder().getOrderNumber())
                .toUri();
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(result.getOrder());
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Get an order with its items")
    public OrderResponse getOrder(@PathVariable String orderNumber) {
        return orderQueryService.getOrder(orderNumber);
    }

    @GetMapping
    @Operation(summary = "Order history of a customer, newest first")
    public PageResponse<OrderSummaryResponse> getHistory(@RequestParam @NotBlank String customerId,
                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                         @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return orderQueryService.getHistory(customerId, page, size);
    }

    @GetMapping("/{orderNumber}/tracking")
    @Operation(summary = "Status history of an order")
    public OrderTrackingResponse getTracking(@PathVariable String orderNumber) {
        return orderQueryService.getTracking(orderNumber);
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Customer cancellation of a CONFIRMED order (triggers inventory restock and refund)")
    public OrderResponse cancel(@PathVariable String orderNumber) {
        return orderStateService.cancelByCustomer(orderNumber);
    }

    @PatchMapping("/{orderNumber}/status")
    @Operation(summary = "Fulfilment update: SHIPPED or DELIVERED")
    public OrderResponse updateStatus(@PathVariable String orderNumber,
                                      @Valid @RequestBody StatusUpdateRequest request) {
        return orderStateService.updateFulfilmentStatus(orderNumber, request.getStatus());
    }
}
