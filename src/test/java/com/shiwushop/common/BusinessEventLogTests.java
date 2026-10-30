package com.shiwushop.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shiwushop.common.logging.BusinessEventLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessEventLogTests {
    @Test
    void emitsOnlyAfterCommitAndKeepsRequestCorrelation() {
        Logger logger = (Logger) LoggerFactory.getLogger(BusinessEventLog.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            MDC.put("requestId", "request-42");
            BusinessEventLog.committed("commerce", "order_paid", "order", 42L);
            assertThat(events.list).isEmpty();

            MDC.remove("requestId");
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains("module=commerce", "action=order_paid", "entityId=42", "outcome=committed");
            assertThat(events.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", "request-42");
        } finally {
            MDC.clear();
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void rollbackDoesNotWriteSuccessEvent() {
        Logger logger = (Logger) LoggerFactory.getLogger(BusinessEventLog.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            BusinessEventLog.committed("catalog", "product_updated", "product", 7L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            assertThat(events.list).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            logger.detachAppender(events);
            events.stop();
        }
    }
}
