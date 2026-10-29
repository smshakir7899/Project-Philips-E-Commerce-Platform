package com.philips.ecommerce.cart.repository;

import com.philips.ecommerce.cart.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
