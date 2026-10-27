package com.shiwushop.commerce.shipment;

import com.shiwushop.commerce.shipment.ShipmentConfirmConsumer;
import com.shiwushop.commerce.shipment.ShipmentMessagingConfig;
import com.shiwushop.commerce.shipment.ShipmentOutboxPublisher;
import com.shiwushop.entity.shipment.Shipment;
import com.shiwushop.mapper.outbox.ConsumedEventMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.shipment.ShipmentMapper;
import com.shiwushop.service.ShipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ShipmentMessagingTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void delayAndWorkQueuesHaveTheExpectedDeadLetterRoutes() {
        ShipmentMessagingConfig config = new ShipmentMessagingConfig();
        assertEquals(ShipmentMessagingConfig.EXCHANGE,
                config.shipmentDelayQueue().getArguments().get("x-dead-letter-exchange"));
        assertEquals(ShipmentMessagingConfig.WORK_ROUTING,
                config.shipmentDelayQueue().getArguments().get("x-dead-letter-routing-key"));
        assertEquals(ShipmentMessagingConfig.DEAD_EXCHANGE,
                config.shipmentWorkQueue().getArguments().get("x-dead-letter-exchange"));
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
    void publisherRetriesAfterFailureAndConsumerSkipsDuplicateEvent() {
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEventMapper.ShipmentConfirmEvent event = event(5L, 8L, 0);
        Shipment shipment = new Shipment();
        shipment.setAutoConfirmAt(LocalDateTime.now(clock).plusDays(10));
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        doThrow(new IllegalStateException("broker down")).when(rabbit).convertAndSend(
                eq(ShipmentMessagingConfig.EXCHANGE), eq(ShipmentMessagingConfig.DELAY_ROUTING),
                eq("5"), any(MessagePostProcessor.class), any(CorrelationData.class));
        new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock).publishPending();
        verify(outbox).markRetry(eq(5L), any(), any());
        verify(outbox, never()).markPublished(eq(5L), any(), any());

        ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
        ShipmentService service = mock(ShipmentService.class);
        when(outbox.selectShipmentEvent(5L)).thenReturn(event);
        when(consumed.insertOnce(eq("shipment-auto-confirm"), eq(5L), any())).thenReturn(1, 0);
        ShipmentConfirmConsumer consumer = new ShipmentConfirmConsumer(outbox, consumed, service, clock);
        consumer.handle("5");
        consumer.handle("5");
        verify(service).confirmDue(8L);
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
