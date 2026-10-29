package com.philips.ecommerce.notification.controller;

import com.philips.ecommerce.notification.dto.FailedEventResponse;
import com.philips.ecommerce.notification.dto.SimulatedFailuresRequest;
import com.philips.ecommerce.notification.dto.SimulatedFailuresResponse;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import com.philips.ecommerce.notification.service.FailedEventService;
import com.philips.ecommerce.notification.service.SimulatedFailureRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * Operator endpoints: dead-letter inspection/replay and the failure simulation switch.
 * (No authentication in this learning project; in production these would require an admin role.)
 */
@RestController
@RequestMapping("/api/notifications/admin")
@Tag(name = "Notification admin", description = "Dead-letter topic records of all consumers, replay, failure simulation")
public class NotificationAdminController {

    private final FailedEventService failedEventService;
    private final SimulatedFailureRegistry simulatedFailureRegistry;

    public NotificationAdminController(FailedEventService failedEventService,
                                       SimulatedFailureRegistry simulatedFailureRegistry) {
        this.failedEventService = failedEventService;
        this.simulatedFailureRegistry = simulatedFailureRegistry;
    }

    @GetMapping("/failed-events")
    @Operation(summary = "Dead-lettered records of ALL consumers, newest first (status optional: NEW or REPLAYED)")
    public List<FailedEventResponse> failedEvents(
            @RequestParam(value = "status", required = false) FailedEventStatus status) {
        return failedEventService.list(status);
    }

    @PostMapping("/failed-events/{id}/replay")
    @Operation(summary = "Republish the payload with its original key to its original topic")
    public FailedEventResponse replay(@PathVariable("id") Long id) {
        return failedEventService.replay(id);
    }

    @GetMapping("/simulated-failures")
    @Operation(summary = "Customers whose notifications currently fail")
    public SimulatedFailuresResponse simulatedFailures() {
        return new SimulatedFailuresResponse(simulatedFailureRegistry.getFailingCustomerIds());
    }

    @PutMapping("/simulated-failures")
    @Operation(summary = "Replace the set of customers whose notifications fail (empty list disables)")
    public SimulatedFailuresResponse updateSimulatedFailures(@Valid @RequestBody SimulatedFailuresRequest request) {
        simulatedFailureRegistry.replaceAll(request.getCustomerIds());
        return new SimulatedFailuresResponse(simulatedFailureRegistry.getFailingCustomerIds());
    }
}
