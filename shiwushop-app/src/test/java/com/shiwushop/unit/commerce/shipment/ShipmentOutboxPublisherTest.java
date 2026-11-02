package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.trade.shipment.ShipmentConfirmConsumer;
import com.shiwushop.trade.shipment.ShipmentMessagingConfig;
import com.shiwushop.trade.shipment.ShipmentOutboxPublisher;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.common.outbox.ConsumedEventMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ShipmentOutboxPublisherTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void interruptionRestoresFlagAndStopsClaimingMoreEvents() {
        var outbox = mock(OutboxEventMapper.class);
        var shipments = mock(ShipmentMapper.class);
        var rabbit = mock(RabbitTemplate.class);
        var shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock).plusDays(10));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event(5L, 8L, 0), event(6L, 9L, 0)));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        doAnswer(call -> { Thread.currentThread().interrupt(); return null; }).when(rabbit)
                .convertAndSend(any(String.class), any(String.class), eq("5"), any(MessagePostProcessor.class), any(CorrelationData.class));
        try {
            new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock).publishPending();
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
            verify(outbox).markRetry(eq(5L), any(), any());
            verify(outbox, never()).claimShipmentEvent(eq(6L), any(), any(), any());
            verify(outbox, never()).markPublished(eq(5L), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void programmingErrorDoesNotBecomeBrokerRetry() {
        var outbox = mock(OutboxEventMapper.class);
        var shipments = mock(ShipmentMapper.class);
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event(5L, 8L, 0)));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenThrow(new IllegalStateException("mapping defect"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ShipmentOutboxPublisher(outbox, shipments, mock(RabbitTemplate.class), clock).publishPending());
        verify(outbox, never()).markRetry(eq(5L), any(), any());
    }

    @Test
    void publisherMarksPublishedOnlyAfterBrokerConfirmation() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventMapper.ShipmentConfirmEvent event = event(5L, 8L, 0);
        Shipment shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock).plusDays(10));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        doAnswer(call -> {
            MessagePostProcessor processor = call.getArgument(3);
            Message message = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
            assertEquals("864000000", message.getMessageProperties().getExpiration());
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            CorrelationData correlation = call.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(ShipmentMessagingConfig.EXCHANGE),
                eq(ShipmentMessagingConfig.DELAY_ROUTING), eq("5"),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock).publishPending();
        verify(outbox).markPublished(eq(5L), any(), any());
        verify(outbox, never()).markRetry(eq(5L), any(), any());
    }

    @Test
    void publisherRetriesAfterBrokerFailure() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventMapper.ShipmentConfirmEvent event = event(5L, 8L, 0);
        Shipment shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock).plusDays(10));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("broker down"))).when(rabbit).convertAndSend(
                eq(ShipmentMessagingConfig.EXCHANGE), eq(ShipmentMessagingConfig.DELAY_ROUTING),
                eq("5"), any(MessagePostProcessor.class), any(CorrelationData.class));
        new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock).publishPending();
        verify(outbox).markRetry(eq(5L), any(), any());
        verify(outbox, never()).markPublished(eq(5L), any(), any());

    }

    @Test
    void publisherDoesNotSendWhenAnotherInstanceClaimedTheEvent() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event(5L, 8L, 0)));

        new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock).publishPending();

        verify(outbox).claimShipmentEvent(eq(5L), any(), any(), any());
        verify(shipments, never()).selectById(any());
        verify(rabbit, never()).convertAndSend(any(), any(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void publisherLeavesUnclaimedEventsForTheNextScanAfterBudget() throws Exception {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        Shipment shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock).plusDays(10));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event(5L, 8L, 0), event(6L, 9L, 0)));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        doAnswer(call -> {
            Thread.sleep(150);
            CorrelationData correlation = call.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(ShipmentMessagingConfig.EXCHANGE),
                eq(ShipmentMessagingConfig.DELAY_ROUTING), eq("5"),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        ShipmentOutboxPublisher publisher = new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock);
        ReflectionTestUtils.setField(publisher, "scanBudgetMillis", 100L);

        publisher.publishPending();

        verify(outbox).markPublished(eq(5L), any(), any());
        verify(outbox, never()).claimShipmentEvent(eq(6L), any(), any(), any());
    }

    private static OutboxEventMapper.ShipmentConfirmEvent event(Long id, Long shipmentId, int attempts) {
        OutboxEventMapper.ShipmentConfirmEvent event = new OutboxEventMapper.ShipmentConfirmEvent();
        event.setId(id);
        event.setShipmentId(shipmentId);
        event.setAttempts(attempts);
        return event;
    }
}
