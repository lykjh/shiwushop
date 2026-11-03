package com.shiwushop.trade.shipment;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.shipment.rocket-enabled", havingValue = "true")
public class ShipmentRocketConfig {
    public static final String TOPIC = "shiwushop-shipment-auto-confirm";
    public static final String TAG = "AUTO_CONFIRM";

    @Bean
    ClientConfiguration shipmentRocketClientConfiguration(
            @Value("${shop.shipment.rocket-endpoints:}") String endpoints,
            @Value("${shop.shipment.rabbit-enabled:false}") boolean rabbit,
            @Value("${shop.shipment.rabbit-publisher-enabled:true}") boolean rabbitPublisher,
            @Value("${shop.shipment.rocket-publisher-enabled:true}") boolean rocketPublisher,
            @Value("${shop.shipment.rocket-preflight-verified:false}") boolean preflightVerified) {
        if (!preflightVerified) throw new IllegalArgumentException("RocketMQ 未通过部署预检：必须由支持的启动入口核对 timer 和 Broker/Proxy 24h 上限");
        if (rabbit && rabbitPublisher && rocketPublisher) throw new IllegalArgumentException("禁止 Rabbit/Rocket 同实例双发布");
        if (endpoints.isBlank() || endpoints.matches(".*:9876(?:[,;].*)?$"))
            throw new IllegalArgumentException("rocket-endpoints 必须为 Proxy gRPC 地址，不能为 NameServer 9876");
        return ClientConfiguration.newBuilder().setEndpoints(endpoints)
                .enableSsl(false).setRequestTimeout(Duration.ofSeconds(5)).setMaxStartupAttempts(1).build();
    }

    @Bean(destroyMethod = "close")
    Producer shipmentRocketProducer(ClientConfiguration shipmentRocketClientConfiguration) throws ClientException {
        return ClientServiceProvider.loadService().newProducerBuilder()
                .setClientConfiguration(shipmentRocketClientConfiguration).setTopics(TOPIC).setMaxAttempts(1).build();
    }

    @Bean(destroyMethod = "close")
    PushConsumer shipmentRocketConsumer(ClientConfiguration shipmentRocketClientConfiguration,
                                       ShipmentConfirmConsumer consumer) throws ClientException {
        return ClientServiceProvider.loadService().newPushConsumerBuilder()
                .setClientConfiguration(shipmentRocketClientConfiguration).setConsumerGroup(TOPIC)
                .setSubscriptionExpressions(Map.of(TOPIC, new FilterExpression(TAG)))
                .setConsumptionThreadCount(1).setMessageListener(message -> {
                    String eventId = StandardCharsets.UTF_8.decode(message.getBody()).toString();
                    log.info("shipment_receive transport=rocket messageId={} attempt={} receivedAt={}",
                            message.getMessageId(), message.getDeliveryAttempt(), java.time.Instant.now());
                    // 调用独立 Spring 事务代理，只有提交成功后才 ACK；SDK 边界异常返回 FAILURE 触发有界重试。
                    try {
                        consumer.handle(eventId);
                        log.info("shipment_commit transport=rocket eventId={} messageId={} committedAt={}",
                                eventId, message.getMessageId(), java.time.Instant.now());
                        return ConsumeResult.SUCCESS;
                    } catch (RuntimeException failure) {
                        log.warn("shipment_consume_failed transport=rocket messageId={}",
                                message.getMessageId(), failure);
                        return ConsumeResult.FAILURE;
                    }
                }).build();
    }
}
