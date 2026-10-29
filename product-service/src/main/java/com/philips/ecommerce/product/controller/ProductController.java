package com.philips.ecommerce.product.controller;

import com.philips.ecommerce.common.dto.ApiErrorResponse;
import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.BadRequestException;
import com.philips.ecommerce.product.dto.ProductDetailResponse;
import com.philips.ecommerce.product.dto.ProductRequest;
import com.philips.ecommerce.product.dto.ProductSearchCriteria;
import com.philips.ecommerce.product.dto.ProductSummaryResponse;
import com.philips.ecommerce.product.service.ProductCommandService;
import com.philips.ecommerce.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Catalog read and write API. Reads are delegated to whichever ProductQueryService is active. */
@Tag(name = "Products", description = "Product catalog")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final int MAX_BATCH_IDS = 100;

    private final ProductQueryService queryService;
    private final ProductCommandService commandService;

    public ProductController(ProductQueryService queryService, ProductCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @Operation(summary = "List / search active products",
            description = "Paged. size <= 100. sort = name|price|createdAt [,asc|desc]. q = full-text search.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of products"),
            @ApiResponse(responseCode = "400", description = "INVALID_SORT_FIELD, INVALID_SORT_DIRECTION, "
                    + "INVALID_PAGE_REQUEST, INVALID_PRICE_RANGE",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<ProductSummaryResponse> list(
            @Parameter(description = "Free-text search over name, brand and description") @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "e.g. name,asc | price,desc | createdAt,desc") @RequestParam(defaultValue = "name,asc") String sort) {
        // A single String keeps "name,asc" intact; a List<String> parameter would be split at the comma.
        return queryService.list(ProductSearchCriteria.of(q, categoryId, minPrice, maxPrice, page, size, sort));
    }

    @Operation(summary = "Product detail by id (also returns inactive products)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ProductDetailResponse getById(@PathVariable Long id) {
        return queryService.getById(id);
    }

    @Operation(summary = "Product detail by SKU")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/sku/{sku}")
    public ProductDetailResponse getBySku(@PathVariable String sku) {
        return queryService.getBySku(sku);
    }

    @Operation(summary = "Batch lookup by ids (includes inactive; unknown ids are omitted; max 100 ids)",
            description = "Used by order-service at checkout for authoritative prices. Never served from cache.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Products in request order"),
            @ApiResponse(responseCode = "400", description = "TOO_MANY_IDS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/batch")
    public List<ProductSummaryResponse> batch(@RequestParam List<Long> ids) {
        // de-duplicate while keeping the caller's order; ignore empty elements such as "1,,2"
        List<Long> distinct = new ArrayList<>(ids.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        if (distinct.size() > MAX_BATCH_IDS) {
            throw new BadRequestException("TOO_MANY_IDS", "At most " + MAX_BATCH_IDS + " ids per request");
        }
        return queryService.getBatch(distinct);
    }

    @Operation(summary = "Create a product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created, Location header points to the product"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "CATEGORY_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "DUPLICATE_SKU",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ProductDetailResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductDetailResponse created = commandService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Replace a product (evicts its cache entries)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND / CATEGORY_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "DUPLICATE_SKU / CONCURRENT_MODIFICATION",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ProductDetailResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return commandService.update(id, request);
    }

    @Operation(summary = "Soft-delete a product (active=false, evicts its cache entries)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deactivated"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        commandService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
