package com.philips.ecommerce.notification.dto;

import lombok.Builder;
import lombok.Value;

/** A record read from a {@code <topic>.DLT} topic plus its decoded diagnostic headers. */
@Value
@Builder
public class DeadLetterRecord {
    String originalTopic;
    Integer originalPartition;
    Long originalOffset;
    String messageKey;
    String payload;
    String exceptionClass;
    String exceptionMessage;
    String consumerGroup;
}
