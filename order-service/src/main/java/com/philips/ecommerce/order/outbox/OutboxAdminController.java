package com.philips.ecommerce.order.outbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders/admin/outbox")
@Tag(name = "Outbox admin", description = "Inspect and retry transactional-outbox events")
public class OutboxAdminController {

    private final OutboxAdminService adminService;

    public OutboxAdminController(OutboxAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @Operation(summary = "List the latest 100 outbox events, optionally filtered by status")
    public List<OutboxEventResponse> list(@RequestParam(required = false) OutboxStatus status) {
        return adminService.list(status);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Reset an outbox event to PENDING so the publisher sends it again")
    public OutboxEventResponse retry(@PathVariable Long id) {
        return adminService.retry(id);
    }
}
