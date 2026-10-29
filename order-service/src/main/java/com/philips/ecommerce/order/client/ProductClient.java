package com.philips.ecommerce.order.client;

import com.philips.ecommerce.order.client.dto.ProductDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "product-service")
public interface ProductClient {

    /** One call for all cart lines (instead of one call per product). Missing ids are simply absent. */
    @GetMapping("/api/products/batch")
    List<ProductDto> getProducts(@RequestParam("ids") List<Long> ids);
}
