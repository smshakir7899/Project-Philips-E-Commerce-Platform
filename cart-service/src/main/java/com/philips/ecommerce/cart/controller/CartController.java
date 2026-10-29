package com.philips.ecommerce.cart.controller;

import com.philips.ecommerce.cart.dto.AddCartItemRequest;
import com.philips.ecommerce.cart.dto.CartResponse;
import com.philips.ecommerce.cart.dto.UpdateCartItemRequest;
import com.philips.ecommerce.cart.service.CartService;
import com.philips.ecommerce.common.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

/**
 * Cart REST API. @Validated activates method validation, so the @Pattern on the customerId path variable is
 * checked (ConstraintViolationException -> 400 VALIDATION_FAILED in common-lib's GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/carts/{customerId}")
@Validated
@RequiredArgsConstructor
@Tag(name = "Carts", description = "Customer shopping carts")
public class CartController {

    static final String CUSTOMER_ID_PATTERN = "^[A-Za-z0-9-]{3,64}$";
    static final String CUSTOMER_ID_MESSAGE = "must be 3-64 characters: letters, digits or '-'";

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "Get the cart of a customer (empty cart if none exists)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cart"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (customerId)",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public CartResponse getCart(@Parameter(example = "CUST-1001")
                                @PathVariable @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
                                String customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product (quantity is added to an existing line; max 10 per line)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated cart"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, PRODUCT_INACTIVE, QUANTITY_LIMIT_EXCEEDED, CURRENCY_MISMATCH",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK, CONCURRENT_MODIFICATION",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRODUCT_SERVICE_UNAVAILABLE, INVENTORY_SERVICE_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public CartResponse addItem(@PathVariable @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
                                String customerId,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(customerId, request);
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Set the quantity of a cart line")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated cart"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, PRODUCT_INACTIVE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "CART_ITEM_NOT_FOUND, PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "PRODUCT_SERVICE_UNAVAILABLE, INVENTORY_SERVICE_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public CartResponse updateItem(@PathVariable @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
                                   String customerId,
                                   @PathVariable @Positive Long productId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItem(customerId, productId, request);
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a line from the cart")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated cart"),
            @ApiResponse(responseCode = "404", description = "CART_ITEM_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public CartResponse removeItem(@PathVariable @Pattern(regexp = CUSTOMER_ID_PATTERN, message = CUSTOMER_ID_MESSAGE)
                                   String customerId,
                                   @PathVariable @Positive Long productId) {
        return cartService.removeItem(customerId, productId);
    }

    @DeleteMapping
    @Operation(summary = "Delete the whole cart (idempotent)")
    @ApiResponse(responseCode = "204", description = "Cart deleted (or did not exist)")
    public ResponseEntity<Void> clearCart(@PathVariable @Pattern(regexp = CUSTOMER_ID_PATTERN,
            message = CUSTOMER_ID_MESSAGE) String customerId) {
        cartService.clearCart(customerId);
        return ResponseEntity.noContent().build();
    }
}
