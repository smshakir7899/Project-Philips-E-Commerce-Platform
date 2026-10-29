package com.philips.ecommerce.order.service;

import com.philips.ecommerce.common.event.CancellationReason;
import com.philips.ecommerce.common.exception.BusinessException;
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
import com.philips.ecommerce.order.client.dto.ReservationDto;
import com.philips.ecommerce.order.client.dto.ReservationRequestDto;
import com.philips.ecommerce.order.dto.AddressDto;
import com.philips.ecommerce.order.dto.CheckoutRequest;
import com.philips.ecommerce.order.dto.OrderItemResponse;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.entity.OrderStatus;
import com.philips.ecommerce.order.exception.PaymentDeclinedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every branch of the checkout saga, with all collaborators mocked. */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final String KEY = "idem-key-0001";
    private static final String CUSTOMER = "CUST-1001";
    private static final String ORDER_NUMBER = "ORD-20260101-ABCDEF01";

    @Mock
    private CartServiceAdapter cartService;
    @Mock
    private ProductServiceAdapter productService;
    @Mock
    private InventoryServiceAdapter inventoryService;
    @Mock
    private PaymentServiceAdapter paymentService;
    @Mock
    private OrderStateService orderStateService;
    @Mock
    private OrderQueryService orderQueryService;

    private CheckoutService checkoutService;
    private CheckoutRequest request;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(cartService, productService, inventoryService, paymentService,
                orderStateService, orderQueryService);
        request = CheckoutRequest.builder()
                .customerId(CUSTOMER)
                .cardToken("tok_visa")
                .shippingAddress(new AddressDto("Jane Doe", "Main St 1", "Amsterdam", "1011AA", "NL"))
                .build();
    }

    // ---------------------------------------------------------------- helpers

    private void givenCart(CartItemDto... items) {
        when(cartService.getCart(CUSTOMER)).thenReturn(CartDto.builder()
                .customerId(CUSTOMER).currency("EUR").items(Arrays.asList(items)).build());
    }

    private static CartItemDto cartItem(long productId, String cartPrice, int quantity) {
        return CartItemDto.builder().productId(productId).sku("SKU-" + productId).productName("P" + productId)
                .unitPrice(new BigDecimal(cartPrice)).quantity(quantity).build();
    }

    private static ProductDto product(long id, String price, boolean active) {
        return ProductDto.builder().id(id).sku("SKU-" + id).name("Product " + id)
                .price(new BigDecimal(price)).currency("EUR").active(active).build();
    }

    private static OrderResponse order(OrderStatus status) {
        return OrderResponse.builder()
                .orderNumber(ORDER_NUMBER)
                .customerId(CUSTOMER)
                .status(status)
                .totalAmount(new BigDecimal("179.98"))
                .currency("EUR")
                .items(Collections.singletonList(OrderItemResponse.builder().productId(1L).sku("SKU-1")
                        .unitPrice(new BigDecimal("89.99")).quantity(2).lineTotal(new BigDecimal("179.98")).build()))
                .build();
    }

    private static PaymentDto payment(String status, String failureReason) {
        return PaymentDto.builder().paymentReference("PAY-0000000000AB").orderNumber(ORDER_NUMBER)
                .status(status).failureReason(failureReason).build();
    }

    /** Cart + prices + order creation succeed (steps 1-4). */
    private void givenOrderCreated() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        givenCart(cartItem(1L, "99.00", 2));
        when(productService.getProducts(anyList())).thenReturn(Collections.singletonList(product(1L, "89.99", true)));
        when(orderStateService.createOrder(any(NewOrderCommand.class))).thenReturn(order(OrderStatus.CREATED));
    }

    /** ... and the stock reservation succeeds (steps 5-6). */
    private void givenInventoryReserved() {
        givenOrderCreated();
        when(inventoryService.reserve(any(ReservationRequestDto.class))).thenReturn(new ReservationDto());
    }

    private static RemoteServiceException remote(int status, String code) {
        return new RemoteServiceException(HttpStatus.valueOf(status), code, "remote " + code, status, "m");
    }

    // ---------------------------------------------------------------- happy path & idempotency

    @Test
    void successfulCheckoutConfirmsOrderPricedFromProductService() {
        givenInventoryReserved();
        when(paymentService.pay(any(PaymentRequestDto.class))).thenReturn(payment("SUCCEEDED", null));
        when(orderStateService.confirm(ORDER_NUMBER, "PAY-0000000000AB")).thenReturn(order(OrderStatus.CONFIRMED));

        CheckoutResult result = checkoutService.checkout(KEY, request);

        assertThat(result.isReplayed()).isFalse();
        assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        ArgumentCaptor<NewOrderCommand> command = ArgumentCaptor.forClass(NewOrderCommand.class);
        verify(orderStateService).createOrder(command.capture());
        assertThat(command.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(command.getValue().getLines()).hasSize(1);
        // product-service price (89.99), not the cart snapshot (99.00)
        assertThat(command.getValue().getLines().get(0).getUnitPrice()).isEqualByComparingTo("89.99");

        ArgumentCaptor<ReservationRequestDto> reservation = ArgumentCaptor.forClass(ReservationRequestDto.class);
        verify(inventoryService).reserve(reservation.capture());
        assertThat(reservation.getValue().getOrderNumber()).isEqualTo(ORDER_NUMBER);
        assertThat(reservation.getValue().getItems().get(0).getQuantity()).isEqualTo(2);

        verify(orderStateService).markInventoryReserved(ORDER_NUMBER);

        ArgumentCaptor<PaymentRequestDto> pay = ArgumentCaptor.forClass(PaymentRequestDto.class);
        verify(paymentService).pay(pay.capture());
        assertThat(pay.getValue().getIdempotencyKey()).isEqualTo("PAY-" + ORDER_NUMBER);
        assertThat(pay.getValue().getAmount()).isEqualByComparingTo("179.98");
        assertThat(pay.getValue().getCardToken()).isEqualTo("tok_visa");
    }

    @Test
    void knownIdempotencyKeyReplaysStoredOrderWithoutRemoteCalls() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.of(order(OrderStatus.PAYMENT_FAILED)));

        CheckoutResult result = checkoutService.checkout(KEY, request);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getOrder().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verifyNoInteractions(cartService, productService, inventoryService, paymentService, orderStateService);
    }

    @Test
    void idempotencyKeyOfAnotherCustomerIsRejected() {
        OrderResponse foreign = order(OrderStatus.CONFIRMED);
        foreign.setCustomerId("CUST-9999");
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentDuplicateKeyReplaysTheWinner() {
        when(orderQueryService.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(order(OrderStatus.CREATED)));
        givenCart(cartItem(1L, "89.99", 2));
        when(productService.getProducts(anyList())).thenReturn(Collections.singletonList(product(1L, "89.99", true)));
        when(orderStateService.createOrder(any(NewOrderCommand.class)))
                .thenThrow(new DataIntegrityViolationException("uk_orders_idempotency_key"));

        CheckoutResult result = checkoutService.checkout(KEY, request);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getOrder().getOrderNumber()).isEqualTo(ORDER_NUMBER);
        verifyNoInteractions(inventoryService, paymentService);
    }

    // ---------------------------------------------------------------- validation (nothing stored)

    @Test
    void emptyCartIsRejected() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(cartService.getCart(CUSTOMER)).thenReturn(CartDto.builder().customerId(CUSTOMER)
                .items(Collections.<CartItemDto>emptyList()).build());

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EMPTY_CART")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        verifyNoInteractions(productService, orderStateService);
    }

    @Test
    void missingProductIsRejected() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        givenCart(cartItem(1L, "10.00", 1), cartItem(2L, "20.00", 1));
        when(productService.getProducts(anyList())).thenReturn(Collections.singletonList(product(1L, "10.00", true)));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PRODUCT")
                .satisfies(e -> assertThat(((BusinessException) e).getDetails())
                        .containsEntry("productId", 2L).containsEntry("reason", "NOT_FOUND"));
        verifyNoInteractions(orderStateService, inventoryService);
    }

    @Test
    void inactiveProductIsRejected() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        givenCart(cartItem(1L, "10.00", 1));
        when(productService.getProducts(anyList())).thenReturn(Collections.singletonList(product(1L, "10.00", false)));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PRODUCT")
                .satisfies(e -> assertThat(((BusinessException) e).getDetails()).containsEntry("reason", "INACTIVE"));
        verifyNoInteractions(orderStateService);
    }

    // ---------------------------------------------------------------- inventory failures

    @Test
    void insufficientStockCancelsOrderAndReturns409WithRemoteDetails() {
        givenOrderCreated();
        RemoteServiceException outOfStock = remote(409, "INSUFFICIENT_STOCK");
        outOfStock.withDetail("productId", 1).withDetail("requested", 2).withDetail("available", 0);
        when(inventoryService.reserve(any(ReservationRequestDto.class))).thenThrow(outOfStock);

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(be.getDetails()).containsEntry("orderNumber", ORDER_NUMBER)
                            .containsEntry("productId", 1).containsEntry("available", 0);
                });
        verify(orderStateService).cancel(eq(ORDER_NUMBER), eq(CancellationReason.INSUFFICIENT_STOCK), anyString());
        verify(orderStateService, never()).markInventoryReserved(anyString());
        verifyNoInteractions(paymentService);
    }

    @Test
    void inventoryTimeoutCancelsOrderWithInventoryErrorAndReturns503() {
        givenOrderCreated();
        when(inventoryService.reserve(any(ReservationRequestDto.class))).thenThrow(new ServiceUnavailableException(
                "INVENTORY_SERVICE_UNAVAILABLE", "inventory-service is unavailable (timeout or connection failure)"));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE");
                    assertThat(be.getDetails()).containsEntry("orderNumber", ORDER_NUMBER);
                });
        // order-cancelled is published even though we do not know whether stock was reserved
        verify(orderStateService).cancel(eq(ORDER_NUMBER), eq(CancellationReason.INVENTORY_ERROR), anyString());
        verify(orderStateService, never()).markInventoryReserved(anyString());
        verifyNoInteractions(paymentService);
    }

    // ---------------------------------------------------------------- payment failures

    @Test
    void declinedPaymentFailsOrderAndReturns402WithOrderNumber() {
        givenInventoryReserved();
        when(paymentService.pay(any(PaymentRequestDto.class))).thenReturn(payment("FAILED", "CARD_DECLINED"));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(PaymentDeclinedException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(be.getCode()).isEqualTo("PAYMENT_DECLINED");
                    assertThat(be.getDetails()).containsEntry("orderNumber", ORDER_NUMBER)
                            .containsEntry("failureReason", "CARD_DECLINED");
                });
        verify(orderStateService).failPayment(ORDER_NUMBER, CancellationReason.PAYMENT_DECLINED, "CARD_DECLINED",
                "PAY-0000000000AB");
        verify(orderStateService, never()).confirm(anyString(), anyString());
    }

    @Test
    void paymentTimeoutFailsOrderWithPaymentErrorAndReturns503() {
        givenInventoryReserved();
        when(paymentService.pay(any(PaymentRequestDto.class))).thenThrow(new ServiceUnavailableException(
                "PAYMENT_SERVICE_UNAVAILABLE", "payment-service is unavailable (timeout or connection failure)"));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("PAYMENT_SERVICE_UNAVAILABLE");
                    assertThat(be.getDetails()).containsEntry("orderNumber", ORDER_NUMBER);
                });
        verify(orderStateService).failPayment(eq(ORDER_NUMBER), eq(CancellationReason.PAYMENT_ERROR), anyString(),
                isNull());
        verify(orderStateService, never()).confirm(anyString(), anyString());
    }

    @Test
    void paymentStillProcessingIsTreatedAsUnknownOutcome() {
        givenInventoryReserved();
        when(paymentService.pay(any(PaymentRequestDto.class))).thenReturn(payment("PROCESSING", null));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getDetails())
                        .containsEntry("orderNumber", ORDER_NUMBER)
                        .containsEntry("paymentReference", "PAY-0000000000AB"));
        verify(orderStateService).failPayment(eq(ORDER_NUMBER), eq(CancellationReason.PAYMENT_ERROR), anyString(),
                eq("PAY-0000000000AB"));
    }

    @Test
    void storedGatewayErrorIsNotReportedAsDecline() {
        givenInventoryReserved();
        when(paymentService.pay(any(PaymentRequestDto.class))).thenReturn(payment("FAILED", "GATEWAY_ERROR"));

        assertThatThrownBy(() -> checkoutService.checkout(KEY, request))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasFieldOrPropertyWithValue("code", "PAYMENT_SERVICE_UNAVAILABLE");
        verify(orderStateService).failPayment(eq(ORDER_NUMBER), eq(CancellationReason.PAYMENT_ERROR), anyString(),
                eq("PAY-0000000000AB"));
    }

    @Test
    void lineQuantitiesAreForwardedForEveryProduct() {
        when(orderQueryService.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        givenCart(cartItem(1L, "10.00", 1), cartItem(2L, "20.00", 3));
        List<ProductDto> products = Arrays.asList(product(1L, "10.00", true), product(2L, "25.00", true));
        when(productService.getProducts(anyList())).thenReturn(products);
        when(orderStateService.createOrder(any(NewOrderCommand.class))).thenReturn(order(OrderStatus.CREATED));
        when(inventoryService.reserve(any(ReservationRequestDto.class))).thenReturn(new ReservationDto());
        when(paymentService.pay(any(PaymentRequestDto.class))).thenReturn(payment("SUCCEEDED", null));
        when(orderStateService.confirm(anyString(), anyString())).thenReturn(order(OrderStatus.CONFIRMED));

        checkoutService.checkout(KEY, request);

        ArgumentCaptor<NewOrderCommand> command = ArgumentCaptor.forClass(NewOrderCommand.class);
        verify(orderStateService).createOrder(command.capture());
        assertThat(command.getValue().getLines()).extracting(OrderLine::getQuantity).containsExactly(1, 3);
        assertThat(command.getValue().getLines().get(1).getUnitPrice()).isEqualByComparingTo("25.00");
        verify(productService).getProducts(Arrays.asList(1L, 2L));
    }
}
