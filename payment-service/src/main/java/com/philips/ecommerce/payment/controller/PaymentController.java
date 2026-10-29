package com.philips.ecommerce.payment.controller;

import com.philips.ecommerce.payment.dto.PaymentProcessingResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.dto.PaymentResponse;
import com.philips.ecommerce.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "SIMULATED payment processing (no real payment gateway)")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 201 for a new attempt (SUCCEEDED or FAILED - a decline is still a recorded attempt, not an HTTP error),
     * 200 when the idempotencyKey was already used (stored payment returned, possibly still PROCESSING).
     */
    @PostMapping
    @Operation(summary = "Process a payment (idempotent by idempotencyKey)")
    public ResponseEntity<PaymentResponse> process(@Valid @RequestBody PaymentRequest request) {
        PaymentProcessingResult result = paymentService.process(request);
        if (!result.isCreated()) {
            return ResponseEntity.ok(result.getPayment());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reference}")
                .buildAndExpand(result.getPayment().getPaymentReference())
                .toUri();
        return ResponseEntity.created(location).body(result.getPayment());
    }

    @GetMapping("/{paymentReference}")
    @Operation(summary = "Get a payment by reference")
    public PaymentResponse get(@PathVariable String paymentReference) {
        return paymentService.getByReference(paymentReference);
    }

    @GetMapping("/order/{orderNumber}")
    @Operation(summary = "Get the latest payment of an order")
    public PaymentResponse getForOrder(@PathVariable String orderNumber) {
        return paymentService.getLatestForOrder(orderNumber);
    }

    @PostMapping("/{paymentReference}/refund")
    @Operation(summary = "Manually refund a SUCCEEDED payment")
    public PaymentResponse refund(@PathVariable String paymentReference) {
        return paymentService.refund(paymentReference);
    }
}
