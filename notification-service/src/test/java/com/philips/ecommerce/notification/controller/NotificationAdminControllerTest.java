package com.philips.ecommerce.notification.controller;

import com.philips.ecommerce.common.exception.ConflictException;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.common.web.CommonWebAutoConfiguration;
import com.philips.ecommerce.notification.dto.FailedEventResponse;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import com.philips.ecommerce.notification.service.FailedEventService;
import com.philips.ecommerce.notification.service.SimulatedFailureRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationAdminController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class NotificationAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FailedEventService failedEventService;

    @MockBean
    private SimulatedFailureRegistry simulatedFailureRegistry;

    @Test
    void listsFailedEventsByStatus() throws Exception {
        when(failedEventService.list(FailedEventStatus.NEW)).thenReturn(Collections.singletonList(failedEvent()));

        mockMvc.perform(get("/api/notifications/admin/failed-events").param("status", "NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].originalTopic").value("order-confirmed"))
                .andExpect(jsonPath("$[0].consumerGroup").value("inventory-service"))
                .andExpect(jsonPath("$[0].status").value("NEW"));
    }

    @Test
    void invalidStatusIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/notifications/admin/failed-events").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void replayReturnsUpdatedEvent() throws Exception {
        FailedEventResponse replayed = failedEvent();
        replayed.setStatus(FailedEventStatus.REPLAYED);
        replayed.setReplayedAt(Instant.parse("2024-05-01T11:00:00Z"));
        when(failedEventService.replay(7L)).thenReturn(replayed);

        mockMvc.perform(post("/api/notifications/admin/failed-events/7/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.replayedAt").value("2024-05-01T11:00:00Z"));
    }

    @Test
    void replayErrorsUseStandardErrorModel() throws Exception {
        when(failedEventService.replay(8L)).thenThrow(new ResourceNotFoundException("FAILED_EVENT_NOT_FOUND", "nope"));
        when(failedEventService.replay(9L)).thenThrow(new ConflictException("FAILED_EVENT_ALREADY_REPLAYED", "again"));

        mockMvc.perform(post("/api/notifications/admin/failed-events/8/replay"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAILED_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/notifications/admin/failed-events/8/replay"));
        mockMvc.perform(post("/api/notifications/admin/failed-events/9/replay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAILED_EVENT_ALREADY_REPLAYED"));
    }

    @Test
    void getsSimulatedFailures() throws Exception {
        when(simulatedFailureRegistry.getFailingCustomerIds()).thenReturn(Arrays.asList("CUST-FAIL"));

        mockMvc.perform(get("/api/notifications/admin/simulated-failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerIds[0]").value("CUST-FAIL"));
    }

    @Test
    void replacesSimulatedFailures() throws Exception {
        when(simulatedFailureRegistry.getFailingCustomerIds()).thenReturn(Arrays.asList("CUST-1", "CUST-2"));

        mockMvc.perform(put("/api/notifications/admin/simulated-failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerIds\":[\"CUST-1\",\"CUST-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerIds.length()").value(2));

        verify(simulatedFailureRegistry).replaceAll(Arrays.asList("CUST-1", "CUST-2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvalidSimulatedFailureIds() throws Exception {
        mockMvc.perform(put("/api/notifications/admin/simulated-failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerIds\":[\"bad id!\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(put("/api/notifications/admin/simulated-failures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(simulatedFailureRegistry, never()).replaceAll(any(Collection.class));
    }

    private FailedEventResponse failedEvent() {
        return FailedEventResponse.builder().id(7L).originalTopic("order-confirmed").messageKey("ORD-1")
                .payload("{}").exceptionMessage("boom").consumerGroup("inventory-service")
                .status(FailedEventStatus.NEW).createdAt(Instant.parse("2024-05-01T10:00:00Z")).build();
    }
}
