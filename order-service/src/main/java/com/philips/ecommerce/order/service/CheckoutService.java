package com.philips.ecommerce.order.service;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.feign.RemoteServiceException;
import com.philips.ecommerce.order.client.CartServiceAdapter;
import com.philips.ecommerce.order.client.InventoryServiceAdapter;
import com.philips.ecommerce.order.client.PaymentServiceAdapter;
import com.philips.ecommerce.order.client.ProductServiceAdapter;
import com.philips.ecommerce.order.client.dto.CartDto;
import com.philips.ecommerce.order.client.dto.CartItemDto;
import com.philips.ecommerce.order.client.dto.PaymentDto;
import com.philips.ecommerce.order.client.dto.PaymentRequestDto;
import com.philips.ecommerce.order.client.dto.ProductDto;
import com.philips.ecommerce.order.client.dto.ReservationItemDto;
import com.philips.ecommerce.order.client.dto.ReservationRequestDto;
import com.philips.ecommerce.order.dto.CheckoutRequest;
import com.philips.ecommerce.order.dto.OrderItemResponse;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.exception.PaymentDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrator of the checkout SAGA (docs/CONTRACTS.md section 7).
 * <p>
 * Deliberately NOT {@code @Transactional}: a saga spans four services, each with its own database, so there is no
 * single ACID transaction to open (and 2PC/XA across services is avoided: it is slow, blocks on the coordinator and
 * is not supported by Kafka). Instead the saga is a sequence of LOCAL transactions in {@link OrderStateService}
 * separated by remote calls, and every failure after the order was stored is answered with a compensating
 * state change + {@code order-cancelled} event (published through the outbox) that the other services react to.
 * <p>
 * Steps: idempotency check -> cart -> authoritative prices -> TX1 create (CREATED) -> reserve stock ->
 * TX2 INVENTORY_RESERVED -> pay -> TX3 CONFIRMED / PAYMENT_FAILED. Failures:
 * <ul>
 *   <li>empty cart / invalid product: 400, nothing stored;</li>
 *   <li>stock rejected: CANCELLED (INSUFFICIENT_STOCK) -> 409;</li>
 *   <li>inventory unavailable: CANCELLED (INVENTORY_ERROR) -> 503. order-cancelled is still published because
 *       the reservation may have been made just before the timeout - inventory releases it if it exists;</li>
 *   <li>payment declined: PAYMENT_FAILED (PAYMENT_DECLINED) -> 402;</li>
 *   <li>payment timeout / 5xx / still PROCESSING: PAYMENT_FAILED (PAYMENT_ERROR) -> 503; payment-service refunds
 *       a late success when it consumes order-cancelled.</li>
 * </ul>
 * If the process dies mid-saga, the order stays CREATED/INVENTORY_RESERVED and inventory-service's reservation TTL
 * (15 min) releases the stock - the saga's safety net.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    static final String DEFAULT_CURRENCY = "EUR";
    /** payment-service's "outcome unknown" failure, reported when its gateway itself failed. */
    static final String GATEWAY_ERROR = "GATEWAY_ERROR";

    private final CartServiceAdapter cartService;
    private final ProductServiceAdapter productService;
    private final InventoryServiceAdapter inventoryService;
    private final PaymentServiceAdapter paymentService;
    private final OrderStateService orderStateService;
    private final OrderQueryService orderQueryService;

    public CheckoutService(CartServiceAdapter cartService, ProductServiceAdapter productService,
                           InventoryServiceAdapter inventoryService, PaymentServiceAdapter paymentService,
                           OrderStateService orderStateService, OrderQueryService orderQueryService) {
        this.cartService = cartService;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.orderStateService = orderStateService;
        this.orderQueryService = orderQueryService;
    }

    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        // 1. Idempotency: a retried request (client timeout, double click) returns the stored order, whatever its
        //    status, instead of creating and charging a second order.
        Optional<OrderResponse> existing = orderQueryService.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        // 2. Cart
        CartDto cart = cartService.getCart(request.getCustomerId());
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BadRequestException("EMPTY_CART", "Cart of customer " + request.getCustomerId() + " is empty");
        }

        // 3. Authoritative prices
        List<OrderLine> lines = priceCartLines(cart);
        String currency = currencyOf(cart);

        // 4. TX1
        OrderResponse order;
        try {
            order = orderStateService.createOrder(new NewOrderCommand(request.getCustomerId(), idempotencyKey,
                    currency, request.getShippingAddress(), lines));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key both passed step 1 concurrently; the UNIQUE constraint on
            // idempotency_key let exactly one insert win. The loser replays the winner's order.
            OrderResponse winner = orderQueryService.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            log.info("Concurrent checkout with Idempotency-Key {} resolved to order {}", idempotencyKey,
                    winner.getOrderNumber());
            return replay(winner, request);
        }
        String orderNumber = order.getOrderNumber();

        // 5. Reserve stock (compensates on failure), 6. TX2
        reserveInventory(order);
        orderStateService.markInventoryReserved(orderNumber);

        // 7. Pay, TX3
        return CheckoutResult.created(pay(order, request.getCardToken()));
    }

    private CheckoutResult replay(OrderResponse stored, CheckoutRequest request) {
        if (!stored.getCustomerId().equals(request.getCustomerId())) {
            // Same key, different customer: a client bug or an attempt to read someone else's order.
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different request");
        }
        log.info("Idempotent replay of order {} (status {})", stored.getOrderNumber(), stored.getStatus());
        return CheckoutResult.replayed(stored);
    }

    /**
     * Prices every cart line with the CURRENT product-service price: the cart only holds a snapshot from when the
     * item was added, and the client must never decide what it pays. Missing or inactive products reject the
     * checkout before anything is stored.
     */
    private List<OrderLine> priceCartLines(CartDto cart) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CartItemDto item : cart.getItems()) {
            ids.add(item.getProductId());
        }
        List<ProductDto> products = productService.getProducts(new ArrayList<>(ids));
        Map<Long, ProductDto> byId = (products == null ? new ArrayList<ProductDto>() : products).stream()
                .collect(Collectors.toMap(ProductDto::getId, Function.identity(), (a, b) -> a));

        String currency = currencyOf(cart);
        List<OrderLine> lines = new ArrayList<>();
        for (CartItemDto item : cart.getItems()) {
            ProductDto product = byId.get(item.getProductId());
            if (product == null) {
                throw invalidProduct(item.getProductId(), "NOT_FOUND", "Product " + item.getProductId()
                        + " no longer exists");
            }
            if (!product.isActive()) {
                throw invalidProduct(item.getProductId(), "INACTIVE", "Product " + product.getSku()
                        + " is no longer sold");
            }
            if (product.getPrice() == null) {
                throw invalidProduct(item.getProductId(), "NO_PRICE", "Product " + product.getSku() + " has no price");
            }
            String productCurrency = product.getCurrency() != null ? product.getCurrency() : DEFAULT_CURRENCY;
            if (!productCurrency.equals(currency)) {
                throw invalidProduct(item.getProductId(), "CURRENCY_MISMATCH", "Product " + product.getSku()
                        + " is priced in " + productCurrency + ", order currency is " + currency);
            }
            if (item.getQuantity() == null || item.getQuantity() < 1) {
                throw invalidProduct(item.getProductId(), "INVALID_QUANTITY", "Invalid quantity for product "
                        + product.getSku());
            }
            if (item.getUnitPrice() != null && item.getUnitPrice().compareTo(product.getPrice()) != 0) {
                // compareTo, not equals: BigDecimal.equals also compares the scale (10.0 != 10.00).
                log.info("Price of {} changed since it was added to the cart of {}: {} -> {}", product.getSku(),
                        cart.getCustomerId(), item.getUnitPrice(), product.getPrice());
            }
            lines.add(new OrderLine(product.getId(), product.getSku(), product.getName(), product.getPrice(),
                    item.getQuantity()));
        }
        return lines;
    }

    private String currencyOf(CartDto cart) {
        return cart.getCurrency() != null ? cart.getCurrency() : DEFAULT_CURRENCY;
    }

    private BusinessException invalidProduct(Long productId, String reason, String message) {
        return new BadRequestException("INVALID_PRODUCT", message)
                .withDetail("productId", productId)
                .withDetail("reason", reason);
    }

    private void reserveInventory(OrderResponse order) {
        String orderNumber = order.getOrderNumber();
        List<ReservationItemDto> items = new ArrayList<>();
        for (OrderItemResponse item : order.getItems()) {
            items.add(new ReservationItemDto(item.getProductId(), item.getQuantity()));
        }
        try {
            inventoryService.reserve(new ReservationRequestDto(orderNumber, items));
        } catch (RemoteServiceException e) {
            // Business rejection (4xx; 5xx were already converted to ServiceUnavailableException by the adapter).
            if (e.isConflict() || e.isNotFound()) {
                // 409 INSUFFICIENT_STOCK, or 404 INVENTORY_NOT_FOUND (no stock record = nothing to sell).
                orderStateService.cancel(orderNumber, CancellationReason.INSUFFICIENT_STOCK, e.getMessage());
                BusinessException conflict = new ConflictException("INSUFFICIENT_STOCK", e.getMessage());
                copyDetails(e, conflict);
                throw conflict.withDetail("orderNumber", orderNumber);
            }
            throw inventoryUnavailable(orderNumber, e);
        } catch (ServiceUnavailableException e) {
            throw inventoryUnavailable(orderNumber, e);
        }
    }

    private BusinessException inventoryUnavailable(String orderNumber, BusinessException cause) {
        orderStateService.cancel(orderNumber, CancellationReason.INVENTORY_ERROR, cause.getMessage());
        return new ServiceUnavailableException("INVENTORY_SERVICE_UNAVAILABLE",
                "Stock could not be reserved, the order was cancelled: " + cause.getMessage(), cause)
                .withDetail("orderNumber", orderNumber);
    }

    private OrderResponse pay(OrderResponse order, String cardToken) {
        String orderNumber = order.getOrderNumber();
        PaymentRequestDto paymentRequest = PaymentRequestDto.builder()
                .orderNumber(orderNumber)
                .customerId(order.getCustomerId())
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .cardToken(cardToken)
                // Deterministic key: every retry of this payment call is recognised by payment-service.
                .idempotencyKey("PAY-" + orderNumber)
                .build();
        PaymentDto payment;
        try {
            payment = paymentService.pay(paymentRequest);
        } catch (BusinessException e) {
            // ServiceUnavailableException (timeout / 5xx / open circuit) or an unexpected 4xx rejection:
            // we cannot know for sure whether money moved -> PAYMENT_ERROR, payment-service refunds if needed.
            throw paymentUnavailable(orderNumber, e.getMessage(), null, e);
        }

        if (PaymentDto.SUCCEEDED.equals(payment.getStatus())) {
            return orderStateService.confirm(orderNumber, payment.getPaymentReference());
        }
        if (PaymentDto.FAILED.equals(payment.getStatus()) && !GATEWAY_ERROR.equals(payment.getFailureReason())) {
            orderStateService.failPayment(orderNumber, CancellationReason.PAYMENT_DECLINED,
                    payment.getFailureReason(), payment.getPaymentReference());
            throw new PaymentDeclinedException("Payment was declined: " + payment.getFailureReason())
                    .withDetail("orderNumber", orderNumber)
                    .withDetail("paymentReference", payment.getPaymentReference())
                    .withDetail("failureReason", payment.getFailureReason());
        }
        // PROCESSING (e.g. a retry after a read timeout found the first attempt still running) or a gateway error.
        throw paymentUnavailable(orderNumber, "Payment outcome unknown (status " + payment.getStatus() + ")",
                payment.getPaymentReference(), null);
    }

    private BusinessException paymentUnavailable(String orderNumber, String detail, String paymentReference,
                                                 Throwable cause) {
        orderStateService.failPayment(orderNumber, CancellationReason.PAYMENT_ERROR, detail, paymentReference);
        BusinessException ex = new ServiceUnavailableException("PAYMENT_SERVICE_UNAVAILABLE",
                "Payment could not be completed, the order was cancelled: " + detail, cause)
                .withDetail("orderNumber", orderNumber);
        if (paymentReference != null) {
            ex.withDetail("paymentReference", paymentReference);
        }
        return ex;
    }

    private void copyDetails(BusinessException from, BusinessException to) {
        for (Map.Entry<String, Object> entry : from.getDetails().entrySet()) {
            to.withDetail(entry.getKey(), entry.getValue());
        }
    }
}
