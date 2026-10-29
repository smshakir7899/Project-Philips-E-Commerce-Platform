package com.philips.ecommerce.cart.controller;

import com.philips.ecommerce.cart.dto.AddCartItemRequest;
import com.philips.ecommerce.cart.dto.CartItemResponse;
import com.philips.ecommerce.cart.dto.CartResponse;
import com.philips.ecommerce.cart.dto.UpdateCartItemRequest;
import com.philips.ecommerce.cart.service.CartService;
import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartController.class)
@Import(GlobalExceptionHandler.class)
class CartControllerWebTest {

    private static final String CUSTOMER = "CUST-1001";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private CartService cartService;

    @Test
    void getCartReturnsItemsAndTotals() throws Exception {
        when(cartService.getCart(CUSTOMER)).thenReturn(cart());

        mockMvc.perform(get("/api/carts/{customerId}", CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER))
                .andExpect(jsonPath("$.items[0].sku").value("PH-SONIC-9900"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(659.98))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.subtotal").value(659.98))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void invalidCustomerIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/carts/{customerId}", "a!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(cartService);
    }

    @Test
    void addItemReturnsTheUpdatedCart() throws Exception {
        when(cartService.addItem(eq(CUSTOMER), any(AddCartItemRequest.class))).thenReturn(cart());

        mockMvc.perform(post("/api/carts/{customerId}/items", CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void quantityAboveTenIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/carts/{customerId}/items", CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
        verifyNoInteractions(cartService);
    }

    @Test
    void businessErrorsKeepTheirStatusAndCode() throws Exception {
        when(cartService.addItem(eq(CUSTOMER), any(AddCartItemRequest.class)))
                .thenThrow(new BadRequestException("QUANTITY_LIMIT_EXCEEDED", "max 10"))
                .thenThrow(new ConflictException("INSUFFICIENT_STOCK", "only 2").withDetail("available", 2))
                .thenThrow(new ServiceUnavailableException("PRODUCT_SERVICE_UNAVAILABLE", "down"));
        String body = "{\"productId\":5,\"quantity\":3}";

        mockMvc.perform(post("/api/carts/{customerId}/items", CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        mockMvc.perform(post("/api/carts/{customerId}/items", CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.available").value(2));
        mockMvc.perform(post("/api/carts/{customerId}/items", CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void updateOfMissingLineReturns404() throws Exception {
        when(cartService.updateItem(eq(CUSTOMER), eq(9L), any(UpdateCartItemRequest.class)))
                .thenThrow(new ResourceNotFoundException("CART_ITEM_NOT_FOUND", "not in cart"));

        mockMvc.perform(put("/api/carts/{customerId}/items/{productId}", CUSTOMER, 9)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void removeItemReturnsTheUpdatedCart() throws Exception {
        when(cartService.removeItem(CUSTOMER, 1L)).thenReturn(cart());

        mockMvc.perform(delete("/api/carts/{customerId}/items/{productId}", CUSTOMER, 1))
                .andExpect(status().isOk());
    }

    @Test
    void clearCartReturns204() throws Exception {
        mockMvc.perform(delete("/api/carts/{customerId}", CUSTOMER))
                .andExpect(status().isNoContent());
        verify(cartService).clearCart(CUSTOMER);
    }

    private static CartResponse cart() {
        CartItemResponse item = CartItemResponse.builder().productId(1L).sku("PH-SONIC-9900")
                .productName("Sonicare 9900").unitPrice(new BigDecimal("329.99")).quantity(2)
                .lineTotal(new BigDecimal("659.98")).build();
        return CartResponse.builder().customerId(CUSTOMER).items(Collections.singletonList(item)).totalQuantity(2)
                .subtotal(new BigDecimal("659.98")).currency("EUR").build();
    }
}
