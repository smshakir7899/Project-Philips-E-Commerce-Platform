package com.philips.ecommerce.notification.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.notification.dto.NotificationResponse;
import com.philips.ecommerce.notification.entity.Notification;
import com.philips.ecommerce.notification.mapper.NotificationMapper;
import com.philips.ecommerce.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/** Read side of the REST API. readOnly transactions let Hibernate skip dirty checking. */
@Service
@Transactional(readOnly = true)
public class NotificationQueryService {

    /** Newest first; id as tie-breaker so paging is stable when two rows share a timestamp. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository repository;
    private final NotificationMapper mapper;

    public NotificationQueryService(NotificationRepository repository, NotificationMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public PageResponse<NotificationResponse> search(String customerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, NEWEST_FIRST);
        Page<Notification> result = StringUtils.hasText(customerId)
                ? repository.findByCustomerId(customerId, pageRequest)
                : repository.findAll(pageRequest);
        return PageResponse.of(result, mapper::toResponse);
    }

    public List<NotificationResponse> findByOrderNumber(String orderNumber) {
        return repository.findByOrderNumberOrderByCreatedAtAscIdAsc(orderNumber).stream()
                .map(mapper::toResponse)
                .collect(Collectors.toList());
    }
}
