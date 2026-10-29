package com.philips.ecommerce.notification.controller;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.web.CommonWebAutoConfiguration;
import com.philips.ecommerce.notification.dto.NotificationResponse;
import com.philips.ecommerce.notification.entity.NotificationChannel;
import com.philips.ecommerce.notification.entity.NotificationStatus;
import com.philips.ecommerce.notification.entity.NotificationType;
import com.philips.ecommerce.notification.service.NotificationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice only; common-lib's auto-configuration is imported so error responses use the real ApiErrorResponse. */
@WebMvcTest(NotificationController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationQueryService queryService;

    @Test
    void searchReturnsPageResponse() throws Exception {
        NotificationResponse item = sample();
        when(queryService.search("CUST-1001", 0, 10)).thenReturn(
                new PageResponse<NotificationResponse>(Collections.singletonList(item), 0, 10, 1, 1, true));

        mockMvc.perform(get("/api/notifications").param("customerId", "CUST-1001").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.content[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.content[0].type").value("ORDER_CONFIRMED"))
                .andExpect(jsonPath("$.content[0].recipient").value("CUST-1001@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void defaultsToFirstPageOfTwenty() throws Exception {
        when(queryService.search(null, 0, 20)).thenReturn(
                new PageResponse<NotificationResponse>(Collections.<NotificationResponse>emptyList(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk());

        verify(queryService).search(null, 0, 20);
    }

    @Test
    void pageSizeAboveHundredIsRejected() throws Exception {
        mockMvc.perform(get("/api/notifications").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(queryService);
    }

    @Test
    void invalidCustomerIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/notifications").param("customerId", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(queryService, never()).search(anyString(), anyInt(), anyInt());
    }

    @Test
    void byOrderReturnsList() throws Exception {
        when(queryService.findByOrderNumber("ORD-1")).thenReturn(Collections.singletonList(sample()));

        mockMvc.perform(get("/api/notifications/order/ORD-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    private NotificationResponse sample() {
        return NotificationResponse.builder().id(1L).eventId("evt-1").customerId("CUST-1001").orderNumber("ORD-1")
                .type(NotificationType.ORDER_CONFIRMED).channel(NotificationChannel.EMAIL)
                .recipient("CUST-1001@example.com").subject("Your order ORD-1 is confirmed").message("...")
                .status(NotificationStatus.SENT).createdAt(Instant.parse("2024-05-01T10:00:00Z")).build();
    }
}
