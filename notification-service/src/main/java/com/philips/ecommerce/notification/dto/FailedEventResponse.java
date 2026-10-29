package com.philips.ecommerce.notification.dto;

import com.philips.ecommerce.notification.entity.FailedEventStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedEventResponse {
    private Long id;
    private String originalTopic;
    private String messageKey;
    private String payload;
    private String exceptionMessage;
    private String consumerGroup;
    private FailedEventStatus status;
    private Instant createdAt;
    private Instant replayedAt;
}
