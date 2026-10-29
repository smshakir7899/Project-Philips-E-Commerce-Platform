package com.philips.ecommerce.notification.repository;

import com.philips.ecommerce.notification.entity.FailedEvent;
import com.philips.ecommerce.notification.entity.FailedEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {

    List<FailedEvent> findByStatus(FailedEventStatus status, Pageable pageable);

    /** Returns a slice of all rows; the Pageable carries the sort and the max result size. */
    List<FailedEvent> findAllBy(Pageable pageable);

    boolean existsByOriginalTopicAndOriginalPartitionAndOriginalOffsetAndConsumerGroup(
            String originalTopic, Integer originalPartition, Long originalOffset, String consumerGroup);
}
