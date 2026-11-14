package com.shiwushop.trade.shipment;

import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShipmentRocketConfigTest {
    final ShipmentRocketConfig config = new ShipmentRocketConfig();

    @Test
    void directStartupWithoutDeploymentPreflightFailsBeforeSdkCreation() {
        new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                .withPropertyValues("shop.shipment.rocket-enabled=true", "shop.shipment.rocket-preflight-verified=false")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("未通过部署预检"));
                });
    }

    @Test
    void rejectsMissingNameServerAndGrpcEndpoint() {
        for (String address : new String[]{"", " ", "localhost:28081"})
            assertThrows(IllegalArgumentException.class, () -> config.shipmentRocketNameServer(address, true));
        assertEquals("localhost:29876", config.shipmentRocketNameServer("localhost:29876", true));
    }

    @Test
    void disabledContextDoesNotCreateSdk() {
        new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                .withPropertyValues("shop.shipment.rocket-enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(RocketMQTemplate.class).isEmpty()));
    }

    @Test
    void templateOwnsProducerLifecycleAndUsesBoundedSend() throws Exception {
        try (var producer = mockConstruction(DefaultMQProducer.class)) {
            var template = config.shipmentRocketTemplate("localhost:29876");
            assertEquals(RocketMQTemplate.class, template.getClass());
            template.afterPropertiesSet();
            template.destroy();
            var instance = producer.constructed().getFirst();
            verify(instance).setNamesrvAddr("localhost:29876");
            verify(instance).setSendMsgTimeout(5000);
            verify(instance).setRetryTimesWhenSendFailed(0);
            verify(instance).start();
            verify(instance).shutdown();
        }
    }

    @Test
    void consumerSubscribesAndBoundsRetries() throws Exception {
        var listener = config.shipmentRocketListener(mock(ShipmentConfirmConsumer.class));
        try (var construction = mockConstruction(DefaultMQPushConsumer.class)) {
            var consumer = config.shipmentRocketConsumer("localhost:29876", listener);
            verify(consumer).subscribe(ShipmentRocketConfig.TOPIC, ShipmentRocketConfig.TAG);
            verify(consumer).setConsumeMessageBatchMaxSize(1);
            verify(consumer).setMaxReconsumeTimes(15);
            verify(consumer).registerMessageListener(listener);
        }
    }

    @Test
    void acknowledgementFollowsSuccessfulTransactionAndFailureRequestsRetry() {
        var business = mock(ShipmentConfirmConsumer.class);
        var listener = config.shipmentRocketListener(business);
        var message = new MessageExt();
        message.setBody("42".getBytes(StandardCharsets.UTF_8));
        message.setMsgId("receipt-42");
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, listener.consumeMessage(List.of(message), null));
        verify(business).handle("42");
        doThrow(new IllegalStateException("transaction rolled back")).when(business).handle("42");
        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(List.of(message), null));
    }

    @Test
    void partialStartupFailureDestroysTemplateProducer() throws Exception {
        try (var producers = mockConstruction(DefaultMQProducer.class);
             var consumers = mockConstruction(DefaultMQPushConsumer.class, (mock, context) ->
                     doThrow(new org.apache.rocketmq.client.exception.MQClientException("failed", null)).when(mock).start())) {
            new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                    .withBean(ShipmentConfirmConsumer.class, () -> mock(ShipmentConfirmConsumer.class))
                    .withPropertyValues("shop.shipment.rocket-enabled=true", "shop.shipment.rocket-preflight-verified=true", "shop.shipment.rocket-name-server=localhost:29876")
                    .run(context -> assertNotNull(context.getStartupFailure()));
            verify(producers.constructed().getFirst()).shutdown();
        }
    }
}
