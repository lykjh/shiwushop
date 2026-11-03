package com.shiwushop.unit.commerce.shipment;

import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.HeartbeatResponse;
import com.shiwushop.trade.shipment.ShipmentRocketHealthIndicator;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.java.impl.ClientImpl;
import org.apache.rocketmq.client.java.impl.ClientManager;
import org.apache.rocketmq.client.java.rpc.RpcFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShipmentRocketHealthTest {
    final ClientImpl client=mock(ClientImpl.class,withSettings().extraInterfaces(PushConsumer.class));
    final ClientManager manager=mock(ClientManager.class);
    final RpcFuture<apache.rocketmq.v2.HeartbeatRequest,HeartbeatResponse> future=mock(RpcFuture.class);
    final ShipmentRocketHealthIndicator health=new ShipmentRocketHealthIndicator((PushConsumer)client);

    ShipmentRocketHealthTest() {
        when(client.isRunning()).thenReturn(true);
        when(client.getClientManager()).thenReturn(manager);
        when(manager.heartbeat(any(),any(),eq(java.time.Duration.ofSeconds(3)))).thenReturn(future);
    }
    @Test void protocolFailureIsDown() throws Exception {
        when(future.get(3,TimeUnit.SECONDS)).thenReturn(HeartbeatResponse.newBuilder()
                .setStatus(apache.rocketmq.v2.Status.newBuilder().setCode(Code.INTERNAL_ERROR)).build());
        assertEquals(Status.DOWN,health.health().getStatus());
    }
    @Test void networkFailureIsDown() throws Exception {
        when(future.get(3,TimeUnit.SECONDS)).thenThrow(new java.util.concurrent.ExecutionException(new java.io.IOException("offline")));
        assertEquals(Status.DOWN,health.health().getStatus());
    }
    @Test void stoppedSdkDoesNotSendHeartbeat() {
        when(client.isRunning()).thenReturn(false);
        when(client.state()).thenReturn(com.google.common.util.concurrent.Service.State.TERMINATED);
        assertEquals(Status.DOWN,health.health().getStatus());
        verifyNoInteractions(manager);
    }
    @Test void interruptionIsPreserved() throws Exception {
        when(future.get(3,TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertEquals(Status.DOWN,health.health().getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
}
