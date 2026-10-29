package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.PaymentDto;
import com.philips.ecommerce.order.client.dto.PaymentRequestDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service")
public interface PaymentClient {

    @PostMapping("/api/payments")
    PaymentDto pay(@RequestBody PaymentRequestDto request);
}
