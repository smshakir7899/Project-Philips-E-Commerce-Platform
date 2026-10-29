package com.philips.ecommerce.inventory.controller;

import com.philips.ecommerce.common.dto.ApiErrorResponse;
import com.philips.ecommerce.inventory.dto.ReservationRequest;
import com.philips.ecommerce.inventory.dto.ReservationResponse;
import com.philips.ecommerce.inventory.service.ReservationResult;
import com.philips.ecommerce.inventory.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;

/** Reservation API used synchronously by order-service during checkout (saga step 5). */
@RestController
@RequestMapping("/api/inventory/reservations")
@Validated
@RequiredArgsConstructor
@Tag(name = "Reservations", description = "Reserve, commit and release stock for orders")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(summary = "Reserve stock for an order (all-or-nothing, idempotent by orderNumber)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reservation created"),
            @ApiResponse(responseCode = "200", description = "A reservation for this orderNumber already existed"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "INVENTORY_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK, details {productId, requested, available}",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationRequest request) {
        ReservationResult result = reservationService.reserve(request);
        if (!result.isCreated()) {
            return ResponseEntity.ok(result.getReservation());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{orderNumber}")
                .buildAndExpand(result.getReservation().getOrderNumber())
                .toUri();
        return ResponseEntity.created(location).body(result.getReservation());
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Get the reservation of an order")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation found"),
            @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ReservationResponse getReservation(@PathVariable String orderNumber) {
        return reservationService.getReservation(orderNumber);
    }

    @PostMapping("/{orderNumber}/commit")
    @Operation(summary = "Commit a reservation (RESERVED -> COMMITTED, idempotent)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Committed (or already committed)"),
            @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "INVALID_RESERVATION_STATE (released/returned)",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ReservationResponse commit(@PathVariable String orderNumber) {
        return reservationService.commit(orderNumber);
    }

    @PostMapping("/{orderNumber}/release")
    @Operation(summary = "Release a reservation (RESERVED -> RELEASED, COMMITTED -> RETURNED i.e. restock; idempotent)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Released / restocked (or already done)"),
            @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ReservationResponse release(@PathVariable String orderNumber) {
        return reservationService.release(orderNumber);
    }
}
