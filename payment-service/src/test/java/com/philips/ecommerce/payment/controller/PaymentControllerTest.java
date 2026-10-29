package com.philips.ecommerce.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.exception.ServiceUnavailableException;
import com.philips.ecommerce.common.web.GlobalExceptionHandler;
import com.philips.ecommerce.payment.dto.PaymentProcessingResult;
import com.philips.ecommerce.payment.dto.PaymentRequest;
import com.philips.ecommerce.payment.dto.PaymentResponse;
import com.philips.ecommerce.payment.entity.PaymentStatus;
import com.philips.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GlobalExceptionHandler is auto-configured by common-lib, which slice tests do not load - hence the @Import. */
@WebMvcTest(controllers = PaymentController.class)
@Import(GlobalExceptionHandler.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private PaymentService paymentService;

    private static PaymentRequest validRequest() {
        return PaymentRequest.builder().orderNumber("ORD-20260101-0000000A").customerId("CUST-1001")
                .amount(new BigDecimal("149.90")).currency("EUR").cardToken("tok_visa")
                .idempotencyKey("PAY-ORD-20260101-0000000A").build();
    }

    private static PaymentResponse response(PaymentStatus status) {
        return PaymentResponse.builder().paymentReference("PAY-ABCDEF123456").orderNumber("ORD-20260101-0000000A")
                .customerId("CUST-1001").amount(new BigDecimal("149.90")).currency("EUR").status(status)
                .createdAt(Instant.parse("2026-01-01T10:00:00Z")).updatedAt(Instant.parse("2026-01-01T10:00:01Z"))
                .build();
    }

    @Test
    void newPaymentReturns201WithLocation() throws Exception {
        when(paymentService.process(any())).thenReturn(
                new PaymentProcessingResult(response(PaymentStatus.SUCCEEDED), true));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/payments/PAY-ABCDEF123456")))
                .andExpect(jsonPath("$.paymentReference").value("PAY-ABCDEF123456"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(149.90));
    }

    @Test
    void replayReturns200() throws Exception {
        when(paymentService.process(any())).thenReturn(
                new PaymentProcessingResult(response(PaymentStatus.PROCESSING), false));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void invalidRequestReturns400WithFieldErrors() throws Exception {
        PaymentRequest invalid = validRequest();
        invalid.setAmount(new BigDecimal("0.00"));
        invalid.setCardToken(" ");

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verify(paymentService, never()).process(any());
    }

    @Test
    void gatewayErrorReturns503() throws Exception {
        when(paymentService.process(any())).thenThrow(
                new ServiceUnavailableException("PAYMENT_GATEWAY_ERROR", "gateway failed"));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_ERROR"));
    }

    @Test
    void unknownPaymentReturns404() throws Exception {
        when(paymentService.getByReference("PAY-UNKNOWN00000"))
                .thenThrow(new ResourceNotFoundException("PAYMENT_NOT_FOUND", "not found"));

        mockMvc.perform(get("/api/payments/PAY-UNKNOWN00000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void latestPaymentOfOrder() throws Exception {
        when(paymentService.getLatestForOrder("ORD-20260101-0000000A")).thenReturn(response(PaymentStatus.FAILED));

        mockMvc.perform(get("/api/payments/order/ORD-20260101-0000000A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void refundOfNonSucceededPaymentReturns409() throws Exception {
        when(paymentService.refund("PAY-ABCDEF123456"))
                .thenThrow(new ConflictException("INVALID_PAYMENT_STATE", "Payment is FAILED"));

        mockMvc.perform(post("/api/payments/PAY-ABCDEF123456/refund"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"));
    }
}
