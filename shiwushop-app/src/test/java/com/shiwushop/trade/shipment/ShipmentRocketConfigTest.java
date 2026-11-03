package com.shiwushop.trade.shipment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;

class ShipmentRocketConfigTest {
    final ShipmentRocketConfig config = new ShipmentRocketConfig();
    @Test void directStartupWithoutDeploymentPreflightFailsBeforeSdkCreation() {
        try (var sdk=mockStatic(ClientServiceProvider.class)) {
            new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                    .withPropertyValues("shop.shipment.rocket-preflight-verified=false","shop.shipment.rocket-enabled=true","shop.shipment.rocket-endpoints=localhost:28081")
                    .run(context -> {
                        assertNotNull(context.getStartupFailure());
                        assertTrue(context.getStartupFailure().getMessage().contains("未通过部署预检"));
                    });
            sdk.verifyNoInteractions();
        }
    }
    @Test void rejectsMissingEndpointAndNameServer() {
        for (String endpoint : new String[]{"", " ", "localhost:9876"})
            assertThrows(IllegalArgumentException.class, () -> config.shipmentRocketClientConfiguration(endpoint, false, true, true, true));
    }
    @Test void rejectsDualPublishButAllowsDrainAndRollback() {
        assertThrows(IllegalArgumentException.class, () -> config.shipmentRocketClientConfiguration("localhost:28081", true, true, true, true));
        assertNotNull(config.shipmentRocketClientConfiguration("localhost:28081", true, false, true, true));
        assertNotNull(config.shipmentRocketClientConfiguration("localhost:28081", true, true, false, true));
    }
    @Test void disabledContextDoesNotCreateSdk() {
        new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                .run(context -> assertTrue(context.getBeansOfType(Producer.class).isEmpty()));
    }
    @Test void contextClosesBothSdkResources() throws Exception {
        var provider=mock(ClientServiceProvider.class);
        var producer=mock(Producer.class);var consumer=mock(PushConsumer.class);
        var producerBuilder=mock(ProducerBuilder.class,RETURNS_SELF);
        var consumerBuilder=mock(PushConsumerBuilder.class,RETURNS_SELF);
        when(provider.newProducerBuilder()).thenReturn(producerBuilder);
        when(provider.newPushConsumerBuilder()).thenReturn(consumerBuilder);
        when(producerBuilder.build()).thenReturn(producer);when(consumerBuilder.build()).thenReturn(consumer);
        try(var service=mockStatic(ClientServiceProvider.class)) {
            service.when(ClientServiceProvider::loadService).thenReturn(provider);
            new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                    .withBean(ShipmentConfirmConsumer.class,()->mock(ShipmentConfirmConsumer.class))
                    .withPropertyValues("shop.shipment.rocket-preflight-verified=true","shop.shipment.rocket-enabled=true","shop.shipment.rocket-endpoints=localhost:28081")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
        verify(consumer).close();verify(producer).close();
    }
    @Test void partialStartupFailureClosesProducer() throws Exception {
        var provider=mock(ClientServiceProvider.class);var producer=mock(Producer.class);
        var producerBuilder=mock(ProducerBuilder.class,RETURNS_SELF);
        var consumerBuilder=mock(PushConsumerBuilder.class,RETURNS_SELF);
        when(provider.newProducerBuilder()).thenReturn(producerBuilder);when(provider.newPushConsumerBuilder()).thenReturn(consumerBuilder);
        when(producerBuilder.build()).thenReturn(producer);
        when(consumerBuilder.build()).thenThrow(new org.apache.rocketmq.client.apis.ClientException("group unavailable"));
        try(var service=mockStatic(ClientServiceProvider.class)) {
            service.when(ClientServiceProvider::loadService).thenReturn(provider);
            new ApplicationContextRunner().withUserConfiguration(ShipmentRocketConfig.class)
                    .withBean(ShipmentConfirmConsumer.class,()->mock(ShipmentConfirmConsumer.class))
                    .withPropertyValues("shop.shipment.rocket-preflight-verified=true","shop.shipment.rocket-enabled=true","shop.shipment.rocket-endpoints=localhost:28081")
                    .run(context -> assertNotNull(context.getStartupFailure()));
        }
        verify(producer).close();
    }
}
