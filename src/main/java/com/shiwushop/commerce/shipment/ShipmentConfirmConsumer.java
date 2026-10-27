package com.shiwushop.commerce.shipment;

import com.shiwushop.mapper.outbox.ConsumedEventMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shop.shipment.rabbit-enabled", havingValue = "true")
public class ShipmentConfirmConsumer {
    private static final String CONSUMER = "shipment-auto-confirm";
    private final OutboxEventMapper outbox;
    private final ConsumedEventMapper consumed;
    private final ShipmentService shipmentService;
    private final Clock clock;

    @RabbitListener(queues = ShipmentMessagingConfig.WORK_QUEUE)
    @Transactional
    public void handle(String eventIdText) {
        Long eventId = Long.valueOf(eventIdText);
        OutboxEventMapper.ShipmentConfirmEvent event = outbox.selectShipmentEvent(eventId);
        if (event == null) throw new IllegalArgumentException("未知物流事件");
        // 消费记录和收货状态同事务提交；MQ 重投时只执行一次确认。
        if (consumed.insertOnce(CONSUMER, eventId, LocalDateTime.now(clock)) == 0) return;
        shipmentService.confirmDue(event.getShipmentId());
    }
}
