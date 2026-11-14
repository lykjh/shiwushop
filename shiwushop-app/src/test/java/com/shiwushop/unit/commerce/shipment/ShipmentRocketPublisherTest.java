package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.shipment.ShipmentOutboxPublisher;
import org.springframework.messaging.Message;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.messaging.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShipmentRocketPublisherTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    final LocalDateTime now = LocalDateTime.now(clock);
    final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
    final ShipmentMapper shipments = mock(ShipmentMapper.class);
    final RocketMQTemplate producer = mock(RocketMQTemplate.class);
    final ShipmentOutboxPublisher publisher = new ShipmentOutboxPublisher(outbox, shipments, producer, clock);
    final OutboxEventMapper.ShipmentConfirmEvent event = new OutboxEventMapper.ShipmentConfirmEvent();
    final Shipment shipment = new Shipment();

    ShipmentRocketPublisherTest() {
        event.setId(5L);
        event.setShipmentId(8L);
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
    }

    @ParameterizedTest
    @ValueSource(longs = {82801, 864000})
    void futureAndLegacyReservationsNeverSendOrIncrementAttempts(long seconds) {
        shipment.setAutoConfirmAt(now.plusSeconds(seconds));
        publisher.publishPending();
        verify(outbox).deferShipmentEvent(eq(5L), any(), eq(shipment.getAutoConfirmAt().minusHours(23)));
        verifyNoInteractions(producer);
        verify(outbox, never()).markRetry(any(), any(), any());
        verify(outbox, never()).markPublished(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {-30, 0, 1, 82799, 82800})
    void sendsAbsoluteDeadlineWithTwoSecondMarginAndReceiptBeforePublish(long seconds) {
        shipment.setAutoConfirmAt(now.plusSeconds(seconds));
        SendResult receipt = mock(SendResult.class);
        when(receipt.getMsgId()).thenReturn("receipt-5");
        when(receipt.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenAnswer(call -> {
            Message<?> message = call.getArgument(1);
            assertEquals(clock.millis() + Math.max(0, seconds) * 1000 + 2000,
                    (long) call.getArgument(2));
            assertEquals("5", message.getPayload());
            verify(outbox, never()).markPublished(any(), any(), any());
            return receipt;
        });
        publisher.publishPending();
        verify(outbox).markPublished(eq(5L), any(), eq(now));
        assertEquals(now.plusSeconds(seconds), shipment.getAutoConfirmAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, 9})
    void unknownSendResultUsesBoundedRetry(int attempts) {
        shipment.setAutoConfirmAt(now);
        event.setAttempts(attempts);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong()))
                .thenThrow(new MessagingException("receipt lost", new java.io.IOException("receipt lost")));
        publisher.publishPending();
        verify(outbox, never()).markPublished(any(), any(), any());
        if (attempts == 9)
            verify(outbox).markDead(eq(5L), any());
        else
            verify(outbox).markRetry(eq(5L), any(), eq(now.plusSeconds(1L << attempts)));
    }

    @Test
    void interruptedWaitRestoresFlag() {
        shipment.setAutoConfirmAt(now);
        // 中断由发送边界发生，不能在扫描入口设置（入口应直接停止领取）。
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenAnswer(call -> {
            throw new MessagingException("interrupted", new InterruptedException());
        });
        try {
            publisher.publishPending();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(outbox).markRetry(eq(5L), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SendStatus.class, names = "SEND_OK", mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void unconfirmedBrokerStatusMustNotMarkPublished(SendStatus status) {
        shipment.setAutoConfirmAt(now);
        var receipt = mock(SendResult.class);
        when(receipt.getSendStatus()).thenReturn(status);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenReturn(receipt);
        publisher.publishPending();
        verify(outbox, never()).markPublished(any(), any(), any());
        verify(outbox).markRetry(eq(5L), any(), any());
    }

    @Test
    void configurationFailureIsDeadAndDoesNotFastRetry() {
        shipment.setAutoConfirmAt(now);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong()))
                .thenThrow(new MessagingException("invalid", new org.apache.rocketmq.client.exception.MQBrokerException(13, "ILLEGAL_DELIVERY_TIME")));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L), any());
        verify(outbox, never()).markRetry(any(), any(), any());
        verify(outbox, never()).markPublished(any(), any(), any());
    }

    @Test
    void synchronousTopicMismatchIsDead() {
        shipment.setAutoConfirmAt(now);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong())).thenThrow(
                new IllegalArgumentException("Current message type not match with topic accept message types"));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L), any());
        verify(outbox, never()).markRetry(any(), any(), any());
        verify(outbox, never()).markPublished(any(), any(), any());
    }

    @Test
    void asynchronousTopicMismatchIsDead() {
        shipment.setAutoConfirmAt(now);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong()))
                .thenThrow(new MessagingException("topic mismatch", new IllegalArgumentException("Topic accepts NORMAL")));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L), any());
        verify(outbox, never()).markRetry(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"accurate timer message is not enabled, timerWheelEnable is false",
            "timer message illegal, the delay time should not be bigger than the max delay 3600000ms"})
    void verifiedBrokerConfigurationErrorsAreNotRetriedAsTransient(String message) {
        shipment.setAutoConfirmAt(now);
        when(producer.syncSendDeliverTimeMills(anyString(), any(org.springframework.messaging.Message.class), anyLong()))
                .thenThrow(new MessagingException(message, new org.apache.rocketmq.client.exception.MQBrokerException(1, message)));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L), any());
        verify(outbox, never()).markRetry(any(), any(), any());
    }
}

