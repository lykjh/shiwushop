package com.shiwushop.trade.shipment;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.ServiceState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

@Component("shipmentRocket")
@ConditionalOnProperty(name = "shop.shipment.rocket-enabled", havingValue = "true")
public class ShipmentRocketHealthIndicator extends AbstractHealthIndicator {
    private final DefaultMQPushConsumer consumer;

    public ShipmentRocketHealthIndicator(DefaultMQPushConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        builder.withDetail("group", ShipmentRocketConfig.TOPIC).withDetail("check", "broker-consumer-connection");
        var implementation = consumer.getDefaultMQPushConsumerImpl();
        if (implementation.getServiceState() != ServiceState.RUNNING) {
            builder.down().withDetail("sdkState", implementation.getServiceState().name());
            return;
        }
        try {
            var queues = consumer.fetchSubscribeMessageQueues(ShipmentRocketConfig.TOPIC);
            var client = implementation.getmQClientFactory();
            for (String broker : queues.stream().map(q -> q.getBrokerName()).distinct().toList()) {
                String address = client.findBrokerAddressInPublish(broker);
                var connections = client.getMQClientAPIImpl().getConsumerConnectionList(
                        address, ShipmentRocketConfig.TOPIC, 3000);
                if (connections.getConnectionSet().stream().noneMatch(c -> client.getClientId().equals(c.getClientId()))) {
                    builder.down().withDetail("broker", broker);
                    return;
                }
            }
            if (queues.isEmpty()) builder.down();
            else builder.up();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
