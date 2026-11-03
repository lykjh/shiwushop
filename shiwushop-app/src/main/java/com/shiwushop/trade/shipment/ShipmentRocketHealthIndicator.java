package com.shiwushop.trade.shipment;

import apache.rocketmq.v2.Code;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.java.impl.ClientImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component("shipmentRocket")
@ConditionalOnProperty(name = "shop.shipment.rocket-enabled", havingValue = "true")
public class ShipmentRocketHealthIndicator extends AbstractHealthIndicator {
    private final ClientImpl client;

    public ShipmentRocketHealthIndicator(PushConsumer consumer) {
        // 原生公共接口没有健康 API；固定实现边界复用已有连接，SDK 升级须重跑实链路测试。
        this.client = (ClientImpl) consumer;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        builder.withDetail("group", ShipmentRocketConfig.TOPIC).withDetail("check", "consumer-heartbeat");
        if (!client.isRunning()) {
            builder.down().withDetail("sdkState", client.state().toString());
            return;
        }
        try {
            var response = client.getClientManager().heartbeat(client.getEndpoints(),
                    client.wrapHeartbeatRequest(), Duration.ofSeconds(3)).get(3, TimeUnit.SECONDS);
            if (response.getStatus().getCode() == Code.OK) builder.up();
            else builder.down().withDetail("code", response.getStatus().getCode().name());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
