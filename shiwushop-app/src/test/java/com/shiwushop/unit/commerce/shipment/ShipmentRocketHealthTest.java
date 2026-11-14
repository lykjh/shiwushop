package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.trade.shipment.*;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.common.ServiceState;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection;
import org.apache.rocketmq.remoting.protocol.body.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShipmentRocketHealthTest {
    final DefaultMQPushConsumer consumer = mock(DefaultMQPushConsumer.class);
    final DefaultMQPushConsumerImpl implementation = mock(DefaultMQPushConsumerImpl.class);
    final MQClientInstance client = mock(MQClientInstance.class);
    final MQClientAPIImpl api = mock(MQClientAPIImpl.class);
    final ShipmentRocketHealthIndicator health = new ShipmentRocketHealthIndicator(consumer);

    ShipmentRocketHealthTest() throws Exception {
        when(consumer.getDefaultMQPushConsumerImpl()).thenReturn(implementation);
        when(implementation.getServiceState()).thenReturn(ServiceState.RUNNING);
        when(implementation.getmQClientFactory()).thenReturn(client);
        when(consumer.fetchSubscribeMessageQueues(ShipmentRocketConfig.TOPIC)).thenReturn(
                Set.of(new MessageQueue(ShipmentRocketConfig.TOPIC, "broker", 0)));
        when(client.findBrokerAddressInPublish("broker")).thenReturn("localhost:29911");
        when(client.getMQClientAPIImpl()).thenReturn(api);
        when(client.getClientId()).thenReturn("consumer-id");
    }

    @Test
    void missingConsumerRegistrationIsDown() throws Exception {
        when(api.getConsumerConnectionList(anyString(), anyString(), eq(3000L))).thenReturn(new ConsumerConnection());
        assertEquals(Status.DOWN, health.health().getStatus());
    }

    @Test
    void actualBrokerRegistrationIsUp() throws Exception {
        var response = new ConsumerConnection();
        var connection = new Connection();
        connection.setClientId("consumer-id");
        response.getConnectionSet().add(connection);
        when(api.getConsumerConnectionList(anyString(), anyString(), eq(3000L))).thenReturn(response);
        assertEquals(Status.UP, health.health().getStatus());
    }

    @Test
    void networkFailureIsDown() throws Exception {
        when(api.getConsumerConnectionList(anyString(), anyString(), eq(3000L))).thenThrow(
                new org.apache.rocketmq.remoting.exception.RemotingConnectException("offline"));
        assertEquals(Status.DOWN, health.health().getStatus());
    }

    @Test
    void stoppedSdkDoesNotQueryBroker() {
        when(implementation.getServiceState()).thenReturn(ServiceState.SHUTDOWN_ALREADY);
        assertEquals(Status.DOWN, health.health().getStatus());
        verifyNoInteractions(api);
    }

    @Test
    void interruptionIsPreserved() throws Exception {
        when(api.getConsumerConnectionList(anyString(), anyString(), eq(3000L))).thenThrow(new InterruptedException());
        try {
            assertEquals(Status.DOWN, health.health().getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
