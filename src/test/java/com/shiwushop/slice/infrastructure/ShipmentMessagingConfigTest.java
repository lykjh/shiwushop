package com.shiwushop.slice.infrastructure;

import com.shiwushop.trade.shipment.ShipmentMessagingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 只装配队列配置，不启动应用、数据库或 RabbitMQ 连接。 */
class ShipmentMessagingConfigTest {
    @Test void delayAndWorkQueuesHaveTheExpectedDeadLetterRoutes() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("shop.shipment.rabbit-enabled","true")));
            context.register(ShipmentMessagingConfig.class); context.refresh();
            var delay=context.getBean("shipmentDelayQueue",Queue.class);
            var work=context.getBean("shipmentWorkQueue",Queue.class);
            var dead=context.getBean("shipmentDeadQueue",Queue.class);
            assertNull(delay.getArguments().get("x-queue-type")); assertNull(work.getArguments().get("x-queue-type"));
            assertNull(dead.getArguments().get("x-queue-type"));
            assertEquals(ShipmentMessagingConfig.EXCHANGE,delay.getArguments().get("x-dead-letter-exchange"));
            assertEquals(ShipmentMessagingConfig.WORK_ROUTING,delay.getArguments().get("x-dead-letter-routing-key"));
            assertEquals(ShipmentMessagingConfig.DEAD_EXCHANGE,work.getArguments().get("x-dead-letter-exchange"));
        }
    }
}
