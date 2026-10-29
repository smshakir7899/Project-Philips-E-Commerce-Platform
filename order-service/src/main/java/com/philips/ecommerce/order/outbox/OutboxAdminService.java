package com.philips.ecommerce.order.outbox;

import com.philips.ecommerce.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** Operator actions on the outbox (inspect stuck/failed rows, re-queue them). */
@Service
public class OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    private final OutboxEventRepository repository;

    public OutboxAdminService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    /** Latest 100 rows, optionally filtered by status. */
    @Transactional(readOnly = true)
    public List<OutboxEventResponse> list(OutboxStatus status) {
        List<OutboxEvent> rows = status == null
                ? repository.findTop100ByOrderByIdDesc()
                : repository.findTop100ByStatusOrderByIdDesc(status);
        return rows.stream().map(OutboxEventResponse::from).collect(Collectors.toList());
    }

    /**
     * Puts a row back to PENDING. Re-sending a SENT row is allowed too (e.g. a consumer lost data); it is safe
     * because consumers are idempotent.
     */
    @Transactional
    public OutboxEventResponse retry(Long id) {
        OutboxEvent row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OUTBOX_EVENT_NOT_FOUND",
                        "Outbox event " + id + " not found"));
        log.info("Outbox: operator re-queued event {} ({} {}, was {})", id, row.getEventType(), row.getEventId(),
                row.getStatus());
        row.resetForRetry();
        return OutboxEventResponse.from(row);
    }
}
