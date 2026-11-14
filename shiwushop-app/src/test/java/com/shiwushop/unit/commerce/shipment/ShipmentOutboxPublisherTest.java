package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.trade.shipment.ShipmentOutboxPublisher;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.messaging.MessagingException;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShipmentOutboxPublisherTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
    final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
    final ShipmentMapper shipments = mock(ShipmentMapper.class);
    final RocketMQTemplate producer = mock(RocketMQTemplate.class);
    final ShipmentOutboxPublisher publisher = new ShipmentOutboxPublisher(outbox, shipments, producer, clock);

    ShipmentOutboxPublisherTest() {
        var shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event(5L, 8L), event(6L, 9L)));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
    }

    @Test
    void interruptionRestoresFlagAndStopsClaimingMoreEvents() {
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new MessagingException("interrupted", new InterruptedException());
        });
        try {
            publisher.publishPending();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(outbox).markRetry(eq(5L), any(), any());
            verify(outbox, never()).claimShipmentEvent(eq(6L), any(), any(), any());
            verify(outbox, never()).markPublished(any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void programmingErrorDoesNotBecomeBrokerRetry() {
        when(shipments.selectById(8L)).thenThrow(new IllegalStateException("mapping defect"));
        assertThrows(IllegalStateException.class, publisher::publishPending);
        verify(outbox, never()).markRetry(any(), any(), any());
    }

    @Test
    void missingShipmentIsDeadWithoutSending() {
        when(shipments.selectById(8L)).thenReturn(null);
        publisher.publishPending();
        verify(outbox).markDead(eq(5L), any());
        verifyNoInteractions(producer);
    }

    @Test
    void publisherDoesNotSendWhenAnotherInstanceClaimedTheEvent() {
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(0);
        publisher.publishPending();
        verifyNoInteractions(shipments, producer);
    }

    @Test
    void publisherLeavesUnclaimedEventsForTheNextScanAfterBudget() {
        var receipt = mock(SendResult.class);
        when(receipt.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenAnswer(call -> {
            Thread.sleep(150);
            return receipt;
        });
        ReflectionTestUtils.setField(publisher, "scanBudgetMillis", 100L);
        publisher.publishPending();
        verify(outbox).markPublished(eq(5L), any(), any());
        verify(outbox, never()).claimShipmentEvent(eq(6L), any(), any(), any());
    }

    private static OutboxEventMapper.ShipmentConfirmEvent event(Long id, Long shipmentId) {
        var event = new OutboxEventMapper.ShipmentConfirmEvent();
        event.setId(id);
        event.setShipmentId(shipmentId);
        return event;
    }
}

