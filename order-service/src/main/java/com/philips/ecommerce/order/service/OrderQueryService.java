package com.philips.ecommerce.order.service;

import com.philips.ecommerce.common.dto.PageResponse;
import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import com.philips.ecommerce.order.dto.OrderResponse;
import com.philips.ecommerce.order.dto.OrderSummaryResponse;
import com.philips.ecommerce.order.dto.OrderTrackingResponse;
import com.philips.ecommerce.order.mapper.OrderMapper;
import com.philips.ecommerce.order.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read side. {@code readOnly = true} lets Hibernate skip dirty checking (no snapshot comparison at flush) and lets
 * the JDBC driver / a routing DataSource use a read replica.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public OrderQueryService(OrderRepository orderRepository, OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
    }

    public Optional<OrderResponse> findByIdempotencyKey(String idempotencyKey) {
        return orderRepository.findWithItemsByIdempotencyKey(idempotencyKey).map(orderMapper::toResponse);
    }

    public OrderResponse getOrder(String orderNumber) {
        return orderRepository.findWithItemsByOrderNumber(orderNumber)
                .map(orderMapper::toResponse)
                .orElseThrow(() -> notFound(orderNumber));
    }

    public PageResponse<OrderSummaryResponse> getHistory(String customerId, int page, int size) {
        Page<OrderSummaryResponse> result =
                orderRepository.findSummariesByCustomerId(customerId, PageRequest.of(page, size));
        return PageResponse.of(result);
    }

    public OrderTrackingResponse getTracking(String orderNumber) {
        return orderRepository.findWithHistoryByOrderNumber(orderNumber)
                .map(orderMapper::toTracking)
                .orElseThrow(() -> notFound(orderNumber));
    }

    private ResourceNotFoundException notFound(String orderNumber) {
        return new ResourceNotFoundException("ORDER_NOT_FOUND", "Order " + orderNumber + " not found");
    }
}
