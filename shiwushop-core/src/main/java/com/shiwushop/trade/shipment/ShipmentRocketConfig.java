package com.shiwushop.trade.shipment;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.shipment.rocket-enabled", havingValue = "true")
public class ShipmentRocketConfig {
    /** 物流自动确认收货消息使用的 RocketMQ 主题。 */
    public static final String TOPIC = "shiwushop-shipment-auto-confirm";
    /** 物流自动确认收货消息的标签，用于订阅过滤。 */
    public static final String TAG = "AUTO_CONFIRM";

    @Bean
    String shipmentRocketNameServer(
            @Value("${shop.shipment.rocket-name-server:}") String nameServer,
            @Value("${shop.shipment.rocket-preflight-verified:false}") boolean preflightVerified) {
        if (!preflightVerified)
            throw new IllegalArgumentException("RocketMQ 未通过部署预检：必须核对 Broker timer 和 24h 上限");
        if (nameServer.isBlank() || nameServer.matches(".*:28081(?:[;].*)?$"))
            throw new IllegalArgumentException("rocket-name-server 必须为 NameServer 地址，不能为 Proxy gRPC 28081");
        return nameServer;
    }

    /** Template 由 Spring 启停，发送超时和重试保持由 outbox 统一控制。 */
    @Bean
    RocketMQTemplate shipmentRocketTemplate(String shipmentRocketNameServer) {
        var producer = new DefaultMQProducer(TOPIC + "-publisher");
        producer.setNamesrvAddr(shipmentRocketNameServer);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(0);
        var template = new RocketMQTemplate();
        template.setProducer(producer);
        template.setMessageConverter(new org.springframework.messaging.converter.StringMessageConverter());
        return template;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    DefaultMQPushConsumer shipmentRocketConsumer(String shipmentRocketNameServer,
                                                MessageListenerConcurrently shipmentRocketListener) throws MQClientException {
        var consumer = new DefaultMQPushConsumer(TOPIC);
        consumer.setNamesrvAddr(shipmentRocketNameServer);
        consumer.setVipChannelEnabled(false);
        consumer.subscribe(TOPIC, TAG);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(15);
        consumer.registerMessageListener(shipmentRocketListener);
        return consumer;
    }

    /** 事务代理返回后才确认消费；异常交给 Broker 重投，eventId 仍由业务事务去重。 */
    @Bean
    MessageListenerConcurrently shipmentRocketListener(ShipmentConfirmConsumer consumer) {
        return (messages, context) -> {
            for (var message : messages) {
                String eventId = new String(message.getBody(), StandardCharsets.UTF_8);
                log.info("shipment_receive transport=rocket messageId={} attempt={} receivedAt={}",
                        message.getMsgId(), message.getReconsumeTimes() + 1, Instant.now());
                try {
                    consumer.handle(eventId);
                    log.info("shipment_commit transport=rocket eventId={} messageId={} committedAt={}",
                            eventId, message.getMsgId(), Instant.now());
                } catch (RuntimeException failure) {
                    log.warn("shipment_consume_failed transport=rocket messageId={}", message.getMsgId(), failure);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        };
    }
}
