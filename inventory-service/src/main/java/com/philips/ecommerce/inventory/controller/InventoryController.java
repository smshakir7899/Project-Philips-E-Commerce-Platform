package com.philips.ecommerce.inventory.controller;

import com.philips.ecommerce.common.dto.ApiErrorResponse;
import com.philips.ecommerce.inventory.dto.AvailabilityResponse;
import com.philips.ecommerce.inventory.dto.InventoryResponse;
import com.philips.ecommerce.inventory.dto.StockUpdateRequest;
import com.philips.ecommerce.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;
import java.util.List;

/**
 * Stock queries and admin updates. @Validated enables method-level validation of path/query parameters
 * (violations -> ConstraintViolationException -> 400 VALIDATION_FAILED via GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/inventory")
@Validated
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Stock levels and availability")
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{productId}")
    @Operation(summary = "Get the stock level of a product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stock found"),
            @ApiResponse(responseCode = "404", description = "INVENTORY_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public InventoryResponse getInventory(@PathVariable @Positive Long productId) {
        return inventoryService.getByProductId(productId);
    }

    @GetMapping
    @Operation(summary = "Get the stock levels of several products (unknown ids are omitted, max 100)")
    @ApiResponse(responseCode = "200", description = "Stock levels")
    public List<InventoryResponse> getInventories(
            @Parameter(description = "Comma-separated product ids", example = "1,2,3")
            @RequestParam @NotEmpty List<Long> productIds) {
        return inventoryService.getByProductIds(productIds);
    }

    @GetMapping("/{productId}/availability")
    @Operation(summary = "Check whether a quantity is currently available (advisory, no reservation)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Availability computed"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "INVENTORY_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public AvailabilityResponse checkAvailability(@PathVariable @Positive Long productId,
                                                  @RequestParam(defaultValue = "1") @Min(1) Integer quantity) {
        return inventoryService.checkAvailability(productId, quantity);
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Create or update the stock level of a product (admin)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stock stored"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "DUPLICATE_SKU or CONCURRENT_MODIFICATION",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public InventoryResponse upsertInventory(@PathVariable @Positive Long productId,
                                             @Valid @RequestBody StockUpdateRequest request) {
        return inventoryService.upsert(productId, request);
    }
}
