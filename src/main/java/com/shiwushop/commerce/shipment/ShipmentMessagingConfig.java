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

    /** 声明物流事件主交换机。 */
    @Bean
    DirectExchange shipmentExchange() { return new DirectExchange(EXCHANGE); }

    /** 声明消费失败时接收死信的交换机。 */
    @Bean
    DirectExchange shipmentDeadExchange() { return new DirectExchange(DEAD_EXCHANGE); }

    /** 延迟队列中的消息到期后转发至实际消费队列。 */
    @Bean
    Queue shipmentDelayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(WORK_ROUTING).build();
    }

    /** 工作队列消费失败后将消息送入死信交换机。 */
    @Bean
    Queue shipmentWorkQueue() {
        return QueueBuilder.durable(WORK_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(DEAD_ROUTING).build();
    }

    /** 保留无法正常消费的物流事件，供人工排查。 */
    @Bean
    Queue shipmentDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }

    /** 将延迟队列绑定到物流主交换机。 */
    @Bean
    Binding shipmentDelayBinding(Queue shipmentDelayQueue, DirectExchange shipmentExchange) {
        return BindingBuilder.bind(shipmentDelayQueue).to(shipmentExchange).with(DELAY_ROUTING);
    }

    /** 将到期消息按工作路由键交给消费者队列。 */
    @Bean
    Binding shipmentWorkBinding(Queue shipmentWorkQueue, DirectExchange shipmentExchange) {
        return BindingBuilder.bind(shipmentWorkQueue).to(shipmentExchange).with(WORK_ROUTING);
    }

    /** 将失败消息按死信路由键送入死信队列。 */
    @Bean
    Binding shipmentDeadBinding(Queue shipmentDeadQueue, DirectExchange shipmentDeadExchange) {
        return BindingBuilder.bind(shipmentDeadQueue).to(shipmentDeadExchange).with(DEAD_ROUTING);
    }
}
