package com.philips.ecommerce.cart.service;

import com.philips.ecommerce.cart.client.InventoryServiceAdapter;
import com.philips.ecommerce.cart.client.ProductServiceAdapter;
import com.philips.ecommerce.cart.client.dto.AvailabilityDto;
import com.philips.ecommerce.cart.client.dto.ProductDto;
import com.philips.ecommerce.cart.dto.AddCartItemRequest;
import com.philips.ecommerce.cart.dto.CartResponse;
import com.philips.ecommerce.cart.dto.UpdateCartItemRequest;
import com.philips.ecommerce.cart.entity.Cart;
import com.philips.ecommerce.cart.repository.CartRepository;
import com.philips.ecommerce.common.exception.BusinessException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business rules of the cart with mocked adapters and repository (TransactionTemplate runs the callbacks). */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final String CUSTOMER = "CUST-1001";

    @Mock
    private CartRepository cartRepository;
    @Mock
    private ProductServiceAdapter productAdapter;
    @Mock
    private InventoryServiceAdapter inventoryAdapter;
    @Mock
    private PlatformTransactionManager transactionManager;

    private CartService service;

    @BeforeEach
    void setUp() {
        service = new CartService(cartRepository, productAdapter, inventoryAdapter, transactionManager);
    }

    @Test
    void getCartReturnsEmptyCartWhenCustomerHasNone() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        CartResponse cart = service.getCart(CUSTOMER);

        assertThat(cart.getItems()).isEmpty();
        assertThat(cart.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(cart.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void addingToANewCartCreatesItWithAPriceSnapshot() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());
        when(productAdapter.getProduct(1L)).thenReturn(product(1L, "19.99", true));
        when(inventoryAdapter.checkAvailability(1L, 3)).thenReturn(available(1L, 3, 50));
        when(cartRepository.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse cart = service.addItem(CUSTOMER, new AddCartItemRequest(1L, 3));

        assertThat(cart.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getSku()).isEqualTo("SKU-1");
        assertThat(cart.getItems().get(0).getUnitPrice()).isEqualByComparingTo("19.99");
        assertThat(cart.getItems().get(0).getLineTotal()).isEqualByComparingTo("59.97");
        assertThat(cart.getSubtotal()).isEqualByComparingTo("59.97");
        assertThat(cart.getSubtotal().scale()).isEqualTo(2);
        assertThat(cart.getTotalQuantity()).isEqualTo(3);
    }

    @Test
    void addingAnExistingProductMergesQuantityAndRefreshesThePrice() {
        Cart existing = cartWith(1L, "19.99", 2);
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(existing));
        when(productAdapter.getProduct(1L)).thenReturn(product(1L, "17.50", true));
        when(inventoryAdapter.checkAvailability(1L, 5)).thenReturn(available(1L, 5, 50));
        when(cartRepository.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse cart = service.addItem(CUSTOMER, new AddCartItemRequest(1L, 3));

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(cart.getItems().get(0).getUnitPrice()).isEqualByComparingTo("17.50");
        assertThat(cart.getSubtotal()).isEqualByComparingTo("87.50");
    }

    @Test
    void exceedingTheLineLimitIsRejectedBeforeAnyRemoteCall() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(cartWith(1L, "19.99", 8)));

        assertThatThrownBy(() -> service.addItem(CUSTOMER, new AddCartItemRequest(1L, 3)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUANTITY_LIMIT_EXCEEDED");
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    assertThat(e.getDetails()).containsEntry("requested", 11).containsEntry("max", 10);
                });
        verifyNoInteractions(productAdapter, inventoryAdapter);
    }

    @Test
    void inactiveProductCannotBeAdded() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());
        when(productAdapter.getProduct(20L)).thenReturn(product(20L, "49.99", false));

        assertThatThrownBy(() -> service.addItem(CUSTOMER, new AddCartItemRequest(20L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_INACTIVE"));
        verifyNoInteractions(inventoryAdapter);
        verify(cartRepository, never()).saveAndFlush(any(Cart.class));
    }

    @Test
    void insufficientStockIsA409WithDetails() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());
        when(productAdapter.getProduct(5L)).thenReturn(product(5L, "139.99", true));
        when(inventoryAdapter.checkAvailability(5L, 3)).thenReturn(new AvailabilityDto(5L, 3, 2, false));

        assertThatThrownBy(() -> service.addItem(CUSTOMER, new AddCartItemRequest(5L, 3)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getDetails()).containsEntry("productId", 5L)
                            .containsEntry("requested", 3)
                            .containsEntry("available", 2);
                });
        verify(cartRepository, never()).saveAndFlush(any(Cart.class));
    }

    @Test
    void productInAnotherCurrencyIsRejected() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(cartWith(1L, "19.99", 1)));
        ProductDto usd = product(2L, "10.00", true);
        usd.setCurrency("USD");
        when(productAdapter.getProduct(2L)).thenReturn(usd);
        when(inventoryAdapter.checkAvailability(2L, 1)).thenReturn(available(2L, 1, 10));

        assertThatThrownBy(() -> service.addItem(CUSTOMER, new AddCartItemRequest(2L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void remoteOutagePropagatesAsServiceUnavailable() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());
        when(productAdapter.getProduct(1L)).thenThrow(
                new ServiceUnavailableException("PRODUCT_SERVICE_UNAVAILABLE", "down"));

        assertThatThrownBy(() -> service.addItem(CUSTOMER, new AddCartItemRequest(1L, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void updateSetsTheAbsoluteQuantityAndRefreshesPrice() {
        Cart existing = cartWith(1L, "19.99", 2);
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(existing));
        when(productAdapter.getProduct(1L)).thenReturn(product(1L, "21.00", true));
        when(inventoryAdapter.checkAvailability(1L, 7)).thenReturn(available(1L, 7, 50));
        when(cartRepository.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse cart = service.updateItem(CUSTOMER, 1L, new UpdateCartItemRequest(7));

        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(7);
        assertThat(cart.getSubtotal()).isEqualByComparingTo("147.00");
    }

    @Test
    void updateOfAMissingLineIs404WithoutRemoteCalls() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(cartWith(1L, "19.99", 2)));

        assertThatThrownBy(() -> service.updateItem(CUSTOMER, 99L, new UpdateCartItemRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CART_ITEM_NOT_FOUND"));
        verifyNoInteractions(productAdapter, inventoryAdapter);
    }

    @Test
    void removeDeletesTheLine() {
        Cart existing = cartWith(1L, "19.99", 2);
        existing.addItem(2L, "SKU-2", "Product 2", new BigDecimal("5.00"), 1);
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.of(existing));
        when(cartRepository.saveAndFlush(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse cart = service.removeItem(CUSTOMER, 1L);

        assertThat(cart.getItems()).extracting("productId").containsExactly(2L);
        assertThat(cart.getSubtotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void removeOfAMissingLineIs404() {
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeItem(CUSTOMER, 1L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CART_ITEM_NOT_FOUND");
                    assertThat(e.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void clearDeletesTheCartAndIsIdempotent() {
        Cart existing = cartWith(1L, "19.99", 2);
        when(cartRepository.findWithItemsByCustomerId(CUSTOMER))
                .thenReturn(Optional.of(existing), Optional.<Cart>empty());

        assertThat(service.clearCart(CUSTOMER)).isTrue();
        assertThat(service.clearCart(CUSTOMER)).isFalse();
        verify(cartRepository).delete(existing);
    }

    // ---------------------------------------------------------------- helpers

    private static ProductDto product(Long id, String price, boolean active) {
        return new ProductDto(id, "SKU-" + id, "Product " + id, new BigDecimal(price), "EUR", active);
    }

    private static AvailabilityDto available(Long productId, int requested, int available) {
        return new AvailabilityDto(productId, requested, available, true);
    }

    private static Cart cartWith(Long productId, String price, int quantity) {
        Cart cart = new Cart(CUSTOMER, "EUR");
        cart.addItem(productId, "SKU-" + productId, "Product " + productId, new BigDecimal(price), quantity);
        return cart;
    }
}
