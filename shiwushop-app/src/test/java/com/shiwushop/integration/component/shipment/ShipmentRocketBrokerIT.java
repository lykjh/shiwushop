package com.shiwushop.integration.component.shipment;

import com.shiwushop.trade.shipment.ShipmentRocketConfig;
import org.apache.rocketmq.client.apis.*;
import org.apache.rocketmq.client.apis.consumer.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立探针 Group 的真实 SDK 重试/DLQ 验证；不替代业务事务测试。 */
class ShipmentRocketBrokerIT {
    @Test void brokerTimerAndLimitRejectWithoutProxyDowngrade() throws Exception {
        var provider=ClientServiceProvider.loadService();
        var cfg=ClientConfiguration.newBuilder().setEndpoints("localhost:28081").enableSsl(false).build();
        try(var producer=provider.newProducerBuilder().setClientConfiguration(cfg).setTopics(ShipmentRocketConfig.TOPIC).setMaxAttempts(1).build()) {
            try {
                admin("updateBrokerConfig","-b","broker:10911","-k","timerWheelEnable","-v","false");
                var disabled=provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("BROKER_PROBE")
                        .setBody("1".getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+5000).build();
                var failure=assertThrows(ClientException.class,()->producer.send(disabled));
                assertInstanceOf(org.apache.rocketmq.client.java.exception.InternalErrorException.class,failure);
                assertTrue(failure.toString().contains("50001"),failure.toString());
                assertTrue(failure.getMessage().contains("accurate timer message is not enabled"),failure.toString());
                System.out.println("ROCKET_TIMER_DISABLED "+failure);
            } finally {admin("updateBrokerConfig","-b","broker:10911","-k","timerWheelEnable","-v","true");}
            assertNotNull(producer.send(provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("BROKER_PROBE")
                    .setBody("1".getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+5000).build()).getMessageId());
            try {
                admin("updateBrokerConfig","-b","broker:10911","-k","timerMaxDelaySec","-v","3600");
                var over=provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("BROKER_PROBE")
                        .setBody("1".getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+23*3600*1000).build();
                var failure=assertThrows(ClientException.class,()->producer.send(over));
                assertInstanceOf(org.apache.rocketmq.client.java.exception.InternalErrorException.class,failure);
                assertTrue(failure.toString().contains("50001"),failure.toString());
                assertTrue(failure.getMessage().contains("timer message illegal"),failure.toString());
                System.out.println("ROCKET_BROKER_LIMIT "+failure);
            } finally {admin("updateBrokerConfig","-b","broker:10911","-k","timerMaxDelaySec","-v","86400");}
            assertNotNull(producer.send(provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("BROKER_PROBE")
                    .setBody("1".getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+23*3600*1000).build()).getMessageId());
        }
    }

    @Test void normalTopicRejectsScheduledMessage() throws Exception {
        admin("updateTopic","-b","broker:10911","-t","shipment-normal-probe","-r","1","-w","1","-a","+message.type=NORMAL");
        var provider=ClientServiceProvider.loadService();
        var cfg=ClientConfiguration.newBuilder().setEndpoints("localhost:28081").enableSsl(false).build();
        try(var producer=provider.newProducerBuilder().setClientConfiguration(cfg).setTopics("shipment-normal-probe").setMaxAttempts(1).build()) {
            var message=provider.newMessageBuilder().setTopic("shipment-normal-probe").setBody("1".getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(System.currentTimeMillis()+5000).build();
            assertThrows(IllegalArgumentException.class,()->producer.send(message));
            var asynchronous = assertThrows(ExecutionException.class,()->producer.sendAsync(message).get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,asynchronous.getCause());
        }
    }

    private void admin(String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("docker","compose","-f","deploy/rocketmq/compose.yml","exec","-T",
                "-e","JAVA_OPT_EXT=-Xms64m -Xmx128m","broker","sh","/opt/rocketmq/bin/mqadmin"));
        command.addAll(List.of(args));command.addAll(List.of("-n","namesrv:9876"));
        var process=new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("logs/rocketmq-migration-20261103/broker-fault-commands.log").toFile())).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)) {process.destroyForcibly();fail("隔离 Broker 管理命令超时");}
        assertEquals(0,process.exitValue());
    }

    @Test void sixteenFailedDeliveriesThenDeadLetterQueue() throws Exception {
        var provider=ClientServiceProvider.loadService();
        var cfg=ClientConfiguration.newBuilder().setEndpoints("localhost:28081")
                .enableSsl(false).setRequestTimeout(Duration.ofSeconds(5)).build();
        String body="bad-probe-"+UUID.randomUUID();
        var attempts=new CopyOnWriteArrayList<Integer>();var completed=new CountDownLatch(16);
        try(var consumer=provider.newPushConsumerBuilder().setClientConfiguration(cfg).setConsumerGroup("rocket-broker-probe")
                .setSubscriptionExpressions(Map.of(ShipmentRocketConfig.TOPIC,new FilterExpression("BROKER_PROBE")))
                .setConsumptionThreadCount(1).setMessageListener(message->{
                    if(StandardCharsets.UTF_8.decode(message.getBody()).toString().equals(body)) {
                        attempts.add(message.getDeliveryAttempt()); completed.countDown();
                    }
                    return ConsumeResult.FAILURE;
                }).build();
            var producer=provider.newProducerBuilder().setClientConfiguration(cfg).setTopics(ShipmentRocketConfig.TOPIC).build()) {
            var receipt=producer.send(provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("BROKER_PROBE")
                    .setKeys(body).setBody(body.getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+2000).build());
            assertTrue(completed.await(90,TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.rangeClosed(1,16).boxed().toList(),attempts);
            Path result=Path.of("logs/rocketmq-migration-20261103/dlq-probe.log");
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();String output="";
            while(System.nanoTime()<deadline) {
                var process=new ProcessBuilder("docker","compose","-f","deploy/rocketmq/compose.yml","exec","-T",
                        "-e","JAVA_OPT_EXT=-Xms64m -Xmx128m","broker","sh","/opt/rocketmq/bin/mqadmin","consumeMessage",
                        "-n","namesrv:9876","-t","%DLQ%rocket-broker-probe","-b","shipment-broker","-i","0","-o","0","-c","100")
                        .redirectErrorStream(true).redirectOutput(result.toFile()).start();
                if(!process.waitFor(20,TimeUnit.SECONDS)) {process.destroyForcibly();fail("DLQ 读取超时");}
                output=Files.readString(result);
                if(output.contains(body)) break;
                Thread.sleep(200);
            }
            assertTrue(output.contains(body),output);
            System.out.printf("ROCKET_DLQ messageId=%s attempts=%s body=%s%n",receipt.getMessageId(),attempts,body);
        }
    }
}
