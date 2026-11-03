package com.shiwushop.trade.shipment;

import com.shiwushop.common.outbox.ConsumedEventMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import com.shiwushop.common.logging.BusinessEventLog;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${shop.shipment.rabbit-enabled:false} || ${shop.shipment.rocket-enabled:false}")
public class ShipmentConfirmConsumer {
    private static final String CONSUMER = "shipment-auto-confirm";
    private final OutboxEventMapper outbox;
    private final ConsumedEventMapper consumed;
    private final ShipmentService shipmentService;
    private final Clock clock;

    /** 消费物流自动确认事件；去重记录和收货状态在同一事务提交。 */
    @RabbitListener(queues = ShipmentMessagingConfig.WORK_QUEUE,
            autoStartup = "${shop.shipment.rabbit-enabled:false}")
    @Transactional
    public void handle(String eventIdText) {
        Long eventId;
        try {
            eventId = Long.valueOf(eventIdText);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("物流事件 ID 必须为正整数文本");
        }
        if (eventId <= 0) throw new IllegalArgumentException("物流事件 ID 必须为正数");
        try (MDC.MDCCloseable ignored = MDC.putCloseable("eventId", eventId.toString())) {
            OutboxEventMapper.ShipmentConfirmEvent event = outbox.selectShipmentEvent(eventId);
            if (event == null) throw new IllegalArgumentException("未知物流事件");
            // 消费记录和收货状态同事务提交；MQ 重投时只执行一次确认。
            if (consumed.insertOnce(CONSUMER, eventId, LocalDateTime.now(clock)) == 0) return;
            // 提前消息必须回滚去重记录；期限判断复用持有业务锁的原确认方法。
            if (!shipmentService.confirmDue(event.getShipmentId()))
                throw new IllegalStateException("物流消息提前到达，eventId=" + eventId);
            BusinessEventLog.committed("trade", "shipment_event_consumed", "event", eventId);
        }
    }
}
