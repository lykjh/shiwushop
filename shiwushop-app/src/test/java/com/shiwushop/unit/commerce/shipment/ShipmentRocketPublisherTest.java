package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.shipment.ShipmentOutboxPublisher;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    final Producer producer = mock(Producer.class);
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final ShipmentOutboxPublisher publisher = new ShipmentOutboxPublisher(outbox, shipments, rabbit, clock);
    final OutboxEventMapper.ShipmentConfirmEvent event = new OutboxEventMapper.ShipmentConfirmEvent();
    final Shipment shipment = new Shipment();

    ShipmentRocketPublisherTest() {
        event.setId(5L); event.setShipmentId(8L);
        when(outbox.selectPendingShipmentEvents(any(), eq(50))).thenReturn(List.of(event));
        when(outbox.claimShipmentEvent(eq(5L), any(), any(), any())).thenReturn(1);
        when(shipments.selectById(8L)).thenReturn(shipment);
        ReflectionTestUtils.setField(publisher, "shipmentRocketProducer", producer);
    }

    @ParameterizedTest @ValueSource(longs = {82801, 864000})
    void futureAndLegacyReservationsNeverSendOrIncrementAttempts(long seconds) {
        shipment.setAutoConfirmAt(now.plusSeconds(seconds)); publisher.publishPending();
        verify(outbox).deferShipmentEvent(eq(5L), any(), eq(shipment.getAutoConfirmAt().minusHours(23)));
        verifyNoInteractions(producer, rabbit);
        verify(outbox, never()).markRetry(any(), any(), any());
        verify(outbox, never()).markPublished(any(), any(), any());
    }

    @ParameterizedTest @ValueSource(longs = {-30, 0, 1, 82799, 82800})
    void sendsAbsoluteDeadlineWithTwoSecondMarginAndReceiptBeforePublish(long seconds) {
        shipment.setAutoConfirmAt(now.plusSeconds(seconds));
        SendReceipt receipt = mock(SendReceipt.class); MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("receipt-5"); when(receipt.getMessageId()).thenReturn(id);
        when(producer.sendAsync(any())).thenAnswer(call -> {
            Message message = call.getArgument(0);
            assertEquals(clock.millis() + Math.max(0, seconds) * 1000 + 2000,
                    message.getDeliveryTimestamp().orElseThrow());
            assertEquals("5", java.nio.charset.StandardCharsets.UTF_8.decode(message.getBody()).toString());
            verify(outbox, never()).markPublished(any(), any(), any());
            return CompletableFuture.completedFuture(receipt);
        });
        publisher.publishPending();
        verify(outbox).markPublished(eq(5L), any(), eq(now)); verifyNoInteractions(rabbit);
        assertEquals(now.plusSeconds(seconds), shipment.getAutoConfirmAt());
    }

    @ParameterizedTest @ValueSource(ints = {0, 8, 9})
    void unknownSendResultUsesBoundedRetry(int attempts) {
        shipment.setAutoConfirmAt(now); event.setAttempts(attempts);
        when(producer.sendAsync(any())).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("receipt lost")));
        publisher.publishPending();
        verify(outbox, never()).markPublished(any(), any(), any());
        if (attempts == 9) verify(outbox).markDead(eq(5L), any());
        else verify(outbox).markRetry(eq(5L), any(), eq(now.plusSeconds(1L << attempts)));
    }

    @Test void interruptedWaitRestoresFlag() {
        shipment.setAutoConfirmAt(now); when(producer.sendAsync(any())).thenReturn(new CompletableFuture<>());
        // 中断由发送边界发生，不能在扫描入口设置（入口应直接停止领取）。
        when(producer.sendAsync(any())).thenAnswer(call -> { Thread.currentThread().interrupt(); return new CompletableFuture<SendReceipt>(); });
        try { publisher.publishPending(); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        verify(outbox).markRetry(eq(5L), any(), any());
    }
    @Test void configurationFailureIsDeadAndDoesNotFastRetry() {
        shipment.setAutoConfirmAt(now);
        when(producer.sendAsync(any())).thenReturn(CompletableFuture.failedFuture(
                new org.apache.rocketmq.client.java.exception.BadRequestException(400,"request-id","ILLEGAL_DELIVERY_TIME")));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L),any());
        verify(outbox,never()).markRetry(any(),any(),any());
        verify(outbox,never()).markPublished(any(),any(),any());
    }
    @Test void synchronousTopicMismatchIsDead() {
        shipment.setAutoConfirmAt(now);
        when(producer.sendAsync(any())).thenThrow(new IllegalArgumentException("Current message type not match with topic accept message types"));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L),any());
        verify(outbox,never()).markRetry(any(),any(),any());
        verify(outbox,never()).markPublished(any(),any(),any());
    }
    @Test void asynchronousTopicMismatchIsDead() {
        shipment.setAutoConfirmAt(now);
        when(producer.sendAsync(any())).thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("Topic accepts NORMAL")));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L),any());verify(outbox,never()).markRetry(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"accurate timer message is not enabled, timerWheelEnable is false",
            "timer message illegal, the delay time should not be bigger than the max delay 3600000ms"})
    void verifiedBrokerConfigurationErrorsAreNotRetriedAsTransient(String message) {
        shipment.setAutoConfirmAt(now);
        when(producer.sendAsync(any())).thenReturn(CompletableFuture.failedFuture(
                new org.apache.rocketmq.client.java.exception.InternalErrorException(50001,"request-id",message)));
        publisher.publishPending();
        verify(outbox).markDead(eq(5L),any());verify(outbox,never()).markRetry(any(),any(),any());
    }
}
