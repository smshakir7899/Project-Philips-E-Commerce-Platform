package com.philips.ecommerce.payment.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OutboxService.class, OutboxAdminService.class, JacksonAutoConfiguration.class})
class OutboxServiceTest {

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OutboxAdminService adminService;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void enqueueRefusesToRunWithoutTransaction() {
        assertThatThrownBy(() -> outboxService.enqueue("payment-processed", "ORD-1", "PaymentProcessedEvent",
                Collections.singletonMap("orderNumber", "ORD-1")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void enqueueInsideTransactionStoresPendingRowAndGeneratesEventId() {
        String eventId = new TransactionTemplate(transactionManager).execute(status ->
                outboxService.enqueue("payment-processed", "ORD-1", "PaymentProcessedEvent",
                        Collections.singletonMap("orderNumber", "ORD-1")));

        OutboxEvent row = repository.findAll().get(0);
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAggregateType()).isEqualTo("Payment");
        assertThat(row.getPayload()).contains("\"eventId\":\"" + eventId + "\"");
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            outboxService.enqueue("payment-processed", "ORD-2", "PaymentProcessedEvent",
                    Collections.singletonMap("orderNumber", "ORD-2"));
            status.setRollbackOnly();
        });

        assertThat(repository.count()).isZero();
    }

    @Test
    void adminRetryResetsFailedEventToPending() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outboxService.enqueue("payment-processed", "ORD-3", "PaymentProcessedEvent",
                        Collections.singletonMap("orderNumber", "ORD-3")));
        OutboxEvent row = repository.findAll().get(0);
        row.markAttemptFailed("boom", 1);
        repository.save(row);
        assertThat(adminService.list(OutboxStatus.FAILED)).hasSize(1);

        OutboxEventResponse retried = adminService.retry(row.getId());

        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getAttempts()).isZero();
        assertThat(adminService.list(OutboxStatus.PENDING)).hasSize(1);
    }
}
