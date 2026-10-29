package com.philips.ecommerce.notification.controller;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.notification.dto.NotificationResponse;
import com.philips.ecommerce.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import java.util.List;

/**
 * Customer-facing read API. {@code @Validated} on the class enables validation of the {@code @RequestParam}
 * constraints (method validation); violations become 400 VALIDATION_FAILED via common-lib's GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/notifications")
@Validated
@Tag(name = "Notifications", description = "Simulated e-mails sent to customers")
public class NotificationController {

    static final int MAX_PAGE_SIZE = 100;

    private final NotificationQueryService queryService;

    public NotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "Notifications, newest first (optionally of one customer)")
    public PageResponse<NotificationResponse> search(
            @RequestParam(value = "customerId", required = false)
            @Pattern(regexp = "^[A-Za-z0-9-]{3,64}$") String customerId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return queryService.search(customerId, page, size);
    }

    @GetMapping("/order/{orderNumber}")
    @Operation(summary = "All notifications of one order, in chronological order")
    public List<NotificationResponse> byOrder(@PathVariable("orderNumber") String orderNumber) {
        return queryService.findByOrderNumber(orderNumber);
    }
}
