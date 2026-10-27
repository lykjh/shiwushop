package com.shiwushop.commerce.shipment;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "shop.shipment.rabbit-enabled", havingValue = "true")
public class ShipmentMessagingConfig {
    public static final String EXCHANGE = "shop.shipment";
    public static final String DELAY_QUEUE = "shop.shipment.confirm.delay";
    public static final String WORK_QUEUE = "shop.shipment.confirm";
    public static final String DEAD_EXCHANGE = "shop.shipment.dead";
    public static final String DEAD_QUEUE = "shop.shipment.confirm.dead";
    public static final String DELAY_ROUTING = "shipment.confirm.delay";
    public static final String WORK_ROUTING = "shipment.confirm";
    public static final String DEAD_ROUTING = "shipment.confirm.dead";

    @Bean
    DirectExchange shipmentExchange() { return new DirectExchange(EXCHANGE); }

    @Bean
    DirectExchange shipmentDeadExchange() { return new DirectExchange(DEAD_EXCHANGE); }

    @Bean
    Queue shipmentDelayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(WORK_ROUTING).build();
    }

    @Bean
    Queue shipmentWorkQueue() {
        return QueueBuilder.durable(WORK_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(DEAD_ROUTING).build();
    }

    @Bean
    Queue shipmentDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }

    @Bean
    Binding shipmentDelayBinding(Queue shipmentDelayQueue, DirectExchange shipmentExchange) {
        return BindingBuilder.bind(shipmentDelayQueue).to(shipmentExchange).with(DELAY_ROUTING);
    }

    @Bean
    Binding shipmentWorkBinding(Queue shipmentWorkQueue, DirectExchange shipmentExchange) {
        return BindingBuilder.bind(shipmentWorkQueue).to(shipmentExchange).with(WORK_ROUTING);
    }

    @Bean
    Binding shipmentDeadBinding(Queue shipmentDeadQueue, DirectExchange shipmentDeadExchange) {
        return BindingBuilder.bind(shipmentDeadQueue).to(shipmentDeadExchange).with(DEAD_ROUTING);
    }
}
