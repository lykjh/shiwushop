package com.shiwushop.integration.component.shipment;

import com.shiwushop.trade.shipment.ShipmentConfirmConsumer;
import com.shiwushop.trade.shipment.ShipmentMessagingConfig;
import com.shiwushop.trade.shipment.ShipmentOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "shop.shipment.rabbit-enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.dynamic=false"
})
class ShipmentRabbitConfigurationTests {
    @Autowired private ApplicationContext context;
    @Autowired private RabbitListenerEndpointRegistry listeners;

    @Test
    void enabledConfigurationWiresPublisherConsumerAndQueues() {
        assertNotNull(context.getBean(ShipmentOutboxPublisher.class));
        assertNotNull(context.getBean(ShipmentConfirmConsumer.class));
        assertTrue(context.getBeansOfType(Queue.class).values().stream()
                .anyMatch(queue -> ShipmentMessagingConfig.DELAY_QUEUE.equals(queue.getName())));
        assertTrue(context.getBeansOfType(Queue.class).values().stream()
                .anyMatch(queue -> ShipmentMessagingConfig.WORK_QUEUE.equals(queue.getName())));
        var container = (SimpleMessageListenerContainer) listeners.getListenerContainers().iterator().next();
        assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
        assertEquals(1, ReflectionTestUtils.getField(container, "maxConcurrentConsumers"));
        assertEquals(1, ReflectionTestUtils.getField(container, "prefetchCount"));
    }
}
