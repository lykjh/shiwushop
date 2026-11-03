package com.shiwushop.system.shipment;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.RocketFaultApp;
import com.shiwushop.trade.shipment.ShipmentRocketConfig;
import org.apache.rocketmq.client.apis.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 只访问固定的独立测试库/端口；所有故障作用于本用例创建的进程、样本和专用 Broker。 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class ShipmentRocketFaultIT {
    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher outcomes=new org.junit.jupiter.api.extension.TestWatcher() {
        @Override public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context,Throwable failure) {
            System.out.println("FAULT_FAILED "+context.getDisplayName());failure.printStackTrace(System.out);
        }
        @Override public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            System.out.println("FAULT_PASSED "+context.getDisplayName());
        }
    };
    final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai","shiwushop","test-shiwushop"));
    final List<Sample> samples=new ArrayList<>();
    record Sample(long event,long shipment,long order,LocalDateTime due) {}
    static final String PUBLISHER="shiwushop-core/src/main/java/com/shiwushop/trade/shipment/ShipmentOutboxPublisher.java";
    static final String CONFIG="shiwushop-core/src/main/java/com/shiwushop/trade/shipment/ShipmentRocketConfig.java";

    @ParameterizedTest @ValueSource(booleans={false,true})
    void aPublisherKilledBeforeSendOrAfterReceipt(boolean afterReceipt) throws Exception {
        try(var app=new RocketFaultApp("kill-publisher-"+afterReceipt,true,false,true,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().plusSeconds(15));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentOutboxPublisher",PUBLISHER,
                        afterReceipt?"if (outbox.markPublished(":"var receipt = shipmentRocketProducer.sendAsync");
                eligible(sample);app.awaitBreak();
                assertEquals("PENDING",state(sample));
                assertNotNull(jdbc.queryForObject("SELECT claim_token FROM outbox_events WHERE id=?",String.class,sample.event));
                snapshot("publisher-paused-"+afterReceipt,sample);
                if(afterReceipt) assertTrue(Files.readString(app.log).contains("shipment_send transport=rocket eventId="+sample.event));
                app.kill();
                try(var recovery=new RocketFaultApp("recover-publisher-"+afterReceipt,true,false,true,false,false)) {
                    await(100,()->state(sample).equals("PUBLISHED") && consumed(sample)==1);
                    awaitPublishedDelivery(recovery,sample);
                    once(sample);unchangedMoney(fixture);snapshot("publisher-recovered-"+afterReceipt,sample);
                }
            } finally {clearEvidenceRows();}
        }
    }

    @Test void bRealCommitFailureRollsBackThenRedelivers() throws Exception {
        try(var app=new RocketFaultApp("commit-failure",true,false,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));
                app.breakOnCommit();String message=send(sample);
                var paused=app.awaitBreak();
                String connections=mysql("SELECT trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_rows_modified>0").trim();
                assertTrue(connections.matches("[0-9]+"),"must have exactly one owned transaction: "+connections);
                mysql("KILL CONNECTION "+connections);
                app.breakAt("com.shiwushop.trade.shipment.ShipmentRocketConfig",CONFIG,"consumer.handle(eventId);");
                paused.resume();
                var retry=app.awaitBreak();
                assertEquals(0,consumed(sample));assertEquals(0,audits(sample));
                assertEquals("SHIPPED",shipmentState(sample));
                assertTrue(Files.readString(app.log).contains("commit"));
                snapshot("real-commit-failed messageId="+message,sample);
                retry.resume();await(30,()->consumed(sample)==1);once(sample);unchangedMoney(fixture);
                snapshot("real-commit-recovered",sample);
            } finally {clearEvidenceRows();}
        }
    }

    @Test void bSendReplyLostAfterBrokerAccepts() throws Exception {
        Path configuration=Path.of("deploy/rocketmq/proxy.json");String original=Files.readString(configuration);
        Files.writeString(configuration,original.replace("\"grpcServerPort\": 28081","\"grpcServerPort\": 28081, \"useEndpointPortFromRequest\": true"));
        command("restart","proxy");command("up","-d","--wait","proxy");
        try(var relay=new com.shiwushop.support.RocketReplyRelay();
            var app=new RocketFaultApp("send-reply-lost",true,false,true,false,false,"localhost:28181");
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().plusSeconds(60));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentOutboxPublisher",PUBLISHER,"var receipt = shipmentRocketProducer.sendAsync");
                eligible(sample);var paused=app.awaitBreak();relay.loseReplies();paused.resume();
                await(12,()->jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,sample.event)>0);
                assertEquals("PENDING",state(sample));
                // 尚未到期的已接收消息索引位于 Broker 定时存储 Topic。
                String accepted=admin("queryMsgByKey","-t","rmq_sys_wheel_timer","-k",Long.toString(sample.event));
                var indexed=java.util.regex.Pattern.compile("(?m)^(\\S+)\\s+\\d+\\s+\\d+\\s+.*@rmq_sys_wheel_timer@K@"+sample.event+"@.*$").matcher(accepted);
                assertTrue(indexed.find(),accepted);
                snapshot("broker-accepted-reply-lost messageId="+indexed.group(1),sample);
                relay.reconnect();await(100,()->state(sample).equals("PUBLISHED")&&consumed(sample)==1);
                awaitPublishedDelivery(app,sample);
                once(sample);unchangedMoney(fixture);snapshot("unknown-send-recovered",sample);
            } finally {clearEvidenceRows();}
        } finally {Files.writeString(configuration,original);command("restart","proxy");command("up","-d","--wait","proxy");}
    }

    @Test void cCommitThenProcessDiesBeforeAck() throws Exception {
        try(var app=new RocketFaultApp("ack-lost",true,false,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentRocketConfig",CONFIG,"log.info(\"shipment_commit");
                String message=send(sample);app.awaitBreak();once(sample);
                snapshot("before-ack-kill messageId="+message,sample);app.kill();
                try(var recovery=new RocketFaultApp("ack-redelivery",true,false,false,false,false)) {
                    await(150,()->read(recovery.log).contains("shipment_commit transport=rocket eventId="+sample.event+" messageId="+message));
                    assertTrue(read(recovery.log).matches("(?s).*messageId="+message+" attempt=[2-9][0-9]* .*"));
                    once(sample);unchangedMoney(fixture);snapshot("ack-redelivered-same-message",sample);
                }
            } finally {clearEvidenceRows();}
        }
    }

    @Test void dMarkPublishedDatabaseFailureRetainsOriginalEvent() throws Exception {
        try(var app=new RocketFaultApp("mark-published-failure",true,false,true,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_mark_failure BEFORE UPDATE ON outbox_events FOR EACH ROW BEGIN IF NEW.id="+sample.event+" AND NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated markPublished failure'; END IF; END");
                try {
                    eligible(sample);await(65,()->jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,sample.event)>0);
                    assertEquals("PENDING",state(sample));snapshot("mark-published-db-failed",sample);
                } finally {mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_mark_failure");}
                await(65,()->state(sample).equals("PUBLISHED") && consumed(sample)==1);
                awaitPublishedDelivery(app,sample);
                once(sample);unchangedMoney(fixture);snapshot("mark-published-recovered",sample);
            } finally {clearEvidenceRows();}
        }
    }

    @Test void eBusinessDlqOriginalEventReplay() throws Exception {
        retryPolicy("fast");
        try(var app=new RocketFaultApp("business-dlq",true,false,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_audit_failure BEFORE INSERT ON audit_events FOR EACH ROW BEGIN IF NEW.target_id="+sample.shipment+" AND NEW.action='SHIPMENT_CONFIRMED_BY_SYSTEM' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated business audit unavailable'; END IF; END");
                String message;
                long sentAt=System.currentTimeMillis();
                try {
                    message=send(sample);
                    await(100,()->read(app.log).contains("messageId="+message+" attempt=16"));
                    var delivered=java.util.regex.Pattern.compile("shipment_receive transport=rocket messageId="+message+" attempt=(\\d+)")
                            .matcher(read(app.log)).results().map(m->Integer.parseInt(m.group(1))).toList();
                    assertEquals(java.util.stream.IntStream.rangeClosed(1,16).boxed().toList(),delivered);
                    String dlq="";
                    for(int i=0;i<6;i++) {
                        dlq=admin("consumeMessage","-t","%DLQ%"+ShipmentRocketConfig.TOPIC,"-b","shipment-broker","-i","0","-s",Long.toString(sentAt),"-c","10000");
                        if(dlq.contains("BODY: "+sample.event))break;
                        Thread.sleep(1000);
                    }
                    assertTrue(java.util.regex.Pattern.compile("(?m)BODY: "+sample.event+"\\r?$").matcher(dlq).find(),dlq);
                    assertTrue(dlq.contains("MSGID: "+message),dlq);
                    assertEquals(0,consumed(sample));assertEquals(0,audits(sample));snapshot("business-dlq messageId="+message,sample);
                } finally {mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_audit_failure");}
                String replay=send(sample);
                snapshot("manual-replay operator=local-acceptance reason=audit-store-restored sourceMessage="+message+" replayMessage="+replay,sample);
                await(30,()->consumed(sample)==1);String duplicate=send(sample);
                await(30,()->read(app.log).contains("shipment_commit transport=rocket eventId="+sample.event+" messageId="+duplicate));
                once(sample);unchangedMoney(fixture);snapshot("business-replay-complete",sample);
            } finally {clearEvidenceRows();}
        } finally {
            retryPolicy("restore");
        }
    }

    @ParameterizedTest @ValueSource(strings={"after-dedupe","before-audit"})
    void dTransactionWriteFailureRollsBackAllRows(String stage) throws Exception {
        try(var app=new RocketFaultApp("transaction-"+stage,true,false,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));
                String target=stage.equals("after-dedupe")?"BEFORE UPDATE ON shipments":"BEFORE INSERT ON audit_events";
                String condition=stage.equals("after-dedupe")?"NEW.id="+sample.shipment:"NEW.target_id="+sample.shipment+" AND NEW.action='SHIPMENT_CONFIRMED_BY_SYSTEM'";
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_stage_failure "+target+" FOR EACH ROW BEGIN IF "+condition+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated transaction stage failure'; END IF; END");
                try {
                    String message=send(sample);
                    await(20,()->read(app.log).contains("shipment_consume_failed transport=rocket messageId="+message));
                    assertEquals(0,consumed(sample));assertEquals(0,audits(sample));assertEquals("SHIPPED",shipmentState(sample));
                    snapshot("rolled-back stage="+stage,sample);
                } finally {mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_stage_failure");}
                await(100,()->consumed(sample)==1);once(sample);unchangedMoney(fixture);snapshot("retry-recovered stage="+stage,sample);
            } finally {clearEvidenceRows();}
        }
    }

    @Test void fHundredCompensatedWhileMqDownForTwoMinutes() throws Exception {
        try(var app=new RocketFaultApp("compensation-100",true,false,true,false,true);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                jdbc.update("UPDATE skus SET stock=120 WHERE id=?",fixture.sku);
                for(int i=0;i<100;i++) ship(fixture,LocalDateTime.now().plusMinutes(10));
                LocalDateTime due=LocalDateTime.now().plusSeconds(45).withNano(0);
                var refunded=ship(fixture,due);
                String tradeNo=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO payment_attempts (order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) VALUES (?,'ALIPAY_SANDBOX',?,'test','test',19.90,'SUCCESS',NOW())",refunded.order,tradeNo);
                long payment=jdbc.queryForObject("SELECT id FROM payment_attempts WHERE out_trade_no=?",Long.class,tradeNo);
                jdbc.update("INSERT INTO after_sale_cases (order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REFUNDED','isolated acceptance',19.90,NOW())",refunded.order);
                long sale=jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?",Long.class,refunded.order);
                jdbc.update("INSERT INTO refund_attempts (case_id,payment_attempt_id,out_request_no,amount,status,requested_at,resolved_at) VALUES (?,?,?,19.90,'SUCCESS',NOW(),NOW())",sale,payment,UUID.randomUUID().toString());
                var provider=ClientServiceProvider.loadService();
                try(var producer=provider.newProducerBuilder().setClientConfiguration(ClientConfiguration.newBuilder()
                        .setEndpoints("localhost:28081").enableSsl(false).build()).setTopics(ShipmentRocketConfig.TOPIC).setMaxAttempts(1).build()) {
                    for(int i=0;i<100;i++) {
                        var old=samples.get(i);var s=new Sample(old.event,old.shipment,old.order,due);samples.set(i,s);
                        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",due,s.shipment);
                        String state=new String[]{"PENDING","PUBLISHED","DEAD"}[i%3];
                        if(state.equals("PUBLISHED")) {
                            var receipt=producer.send(provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag(ShipmentRocketConfig.TAG)
                                    .setKeys(Long.toString(s.event)).setBody(Long.toString(s.event).getBytes(StandardCharsets.UTF_8))
                                    .setDeliveryTimestamp(due.plusSeconds(2).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()).build());
                            snapshot("accepted-before-outage messageId="+receipt.getMessageId(),s);
                        }
                        jdbc.update("UPDATE outbox_events SET status=?,next_attempt_at=? WHERE id=?",state,due,s.event);
                        snapshot("outage-initial state="+state,s);
                    }
                }
                assertEquals(101,jdbc.queryForObject("SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id=s.order_id WHERE o.user_id=? AND s.status='SHIPPED'",Integer.class,fixture.buyer.id()));
                command("stop","-t","0","proxy","broker");
                long stopped=System.nanoTime();Instant stoppedAt=Instant.now();
                try {
                    await(180,()->jdbc.queryForObject("SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id=s.order_id WHERE o.user_id=? AND s.status='CONFIRMED'",Integer.class,fixture.buyer.id())==100);
                    long elapsed=Duration.ofNanos(System.nanoTime()-stopped).toMillis();
                    for(var s:samples.subList(0,100)) {assertEquals(1,audits(s));assertEquals(0,consumed(s));snapshot("scan-only elapsedMs="+elapsed,s);}
                    assertEquals("SHIPPED",shipmentState(refunded));assertEquals(0,audits(refunded));
                    assertTrue(elapsed<=180000);
                    while(System.nanoTime()-stopped<Duration.ofMinutes(2).toNanos()) Thread.sleep(500);
                    Files.writeString(RocketFaultApp.EVIDENCE.resolve("outage-timing.log"),"stoppedAt="+stoppedAt+" restartRequestedAt="+Instant.now()+" confirmedDownMs="+Duration.ofNanos(System.nanoTime()-stopped).toMillis()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                } finally {command("start","broker","proxy");}
                await(180,()->java.util.stream.IntStream.range(0,100).filter(i->i%3!=2)
                        .allMatch(i->state(samples.get(i)).equals("PUBLISHED") && consumed(samples.get(i))==1));
                for(var s:samples.subList(0,100)) {assertEquals(1,audits(s));assertEquals(s.due,jdbc.queryForObject("SELECT auto_confirm_at FROM shipments WHERE id=?",LocalDateTime.class,s.shipment));snapshot("after-mq-recovery",s);}
                assertEquals("SHIPPED",shipmentState(refunded));assertEquals(0,audits(refunded));snapshot("refund-still-excluded",refunded);
                assertEquals("SUCCESS",jdbc.queryForObject("SELECT status FROM refund_attempts WHERE case_id=?",String.class,sale));
                assertEquals(new java.math.BigDecimal("19.90"),jdbc.queryForObject("SELECT amount FROM refund_attempts WHERE case_id=?",java.math.BigDecimal.class,sale));
                for(var s:samples) {
                    assertEquals("PAID",jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,s.order));
                    assertEquals(new java.math.BigDecimal("19.90"),jdbc.queryForObject("SELECT total_amount FROM orders WHERE id=?",java.math.BigDecimal.class,s.order));
                }
                assertEquals(19,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
                assertEquals(101,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?",Integer.class,fixture.sku));
            } finally {clearEvidenceRows();}
        }
    }

    @Test void gIsolatedLegacyInventoryCutoverAndRollback() throws Exception {
        try(var old=new RocketFaultApp("cutover-old-consumer",false,true,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                cleanupCancelledRabbitCopies();
                var far=ship(fixture,LocalDateTime.now().plusDays(2));
                var pending=ship(fixture,LocalDateTime.now().plusSeconds(150));
                var waiting=ship(fixture,pending.due.plusSeconds(10));
                var confirmed=ship(fixture,LocalDateTime.now().minusSeconds(1));
                var dead=ship(fixture,pending.due);
                var unknown=ship(fixture,pending.due);
                rabbitSend(confirmed,false);await(15,()->consumed(confirmed)==1);
                jdbc.update("UPDATE outbox_events SET status='PUBLISHED' WHERE id IN (?,?)",waiting.event,confirmed.event);
                jdbc.update("UPDATE outbox_events SET status='DEAD' WHERE id=?",dead.event);
                // 近期在前，避免旧 TTL 队列的队头阻塞掩盖责任核对。
                rabbitSend(unknown,true);rabbitSend(waiting,true);rabbitSend(far,true);
                Map<Sample,String> responsibility=new LinkedHashMap<>();
                responsibility.put(far,"old-delayed-copy+DB-reservation; keep Rabbit volume");
                responsibility.put(pending,"new-publisher-takeover");
                responsibility.put(waiting,"old-Rabbit-drain; do-not-reset-PUBLISHED");
                responsibility.put(confirmed,"terminal-with-consumed-and-audit");
                responsibility.put(dead,"manual-takeover-after-review");
                responsibility.put(unknown,"unknown-old-send; original-ID-dedup-across-both");
                for(var entry:responsibility.entrySet()) snapshot("inventory owner="+entry.getValue(),entry.getKey());
                old.kill();
                try(var cutover=new RocketFaultApp("cutover-new-publish-old-drain",true,true,true,false,false)) {
                    eligible(pending);eligible(unknown);eligible(far);
                    jdbc.update("UPDATE outbox_events SET status='PENDING',next_attempt_at=NOW() WHERE id=? AND status='DEAD'",dead.event);
                    snapshot("takeover operator=local-acceptance reason=isolated-dead-reviewed",dead);
                    await(65,()->state(pending).equals("PUBLISHED")&&state(dead).equals("PUBLISHED")&&state(unknown).equals("PUBLISHED"));
                    assertEquals(0,jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,far.event));
                    assertEquals("SHIPPED",shipmentState(pending));
                    for(var s:samples)snapshot("cutover-new-published",s);
                    cutover.kill();
                }
                var publishedAttempts=new HashMap<Long,Integer>();
                for(var s:List.of(pending,waiting,dead,unknown)) publishedAttempts.put(s.event,jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,s.event));
                try(var rollback=new RocketFaultApp("rollback-old-publish-new-drain",true,true,false,true,true)) {
                    var back=ship(fixture,LocalDateTime.now().plusSeconds(15));eligible(back);
                    await(180,()->List.of(pending,waiting,dead,unknown,back).stream().allMatch(s->shipmentState(s).equals("CONFIRMED")));
                    for(var s:List.of(pending,waiting,dead,unknown,back))snapshot("rollback-before-duplicate",s);
                    // 到期后的同 ID 跨通道真实重投，再验证每单一份业务审计。
                    for(var s:List.of(pending,waiting,dead,unknown,back)) {rabbitSend(s,false);send(s);}
                    Thread.sleep(4000);
                    for(var s:List.of(pending,waiting,confirmed,dead,unknown,back)) {once(s);snapshot("rollback-final owner=terminal",s);}
                    for(var entry:publishedAttempts.entrySet()) assertEquals(entry.getValue(),jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,entry.getKey()));
                    assertEquals("SHIPPED",shipmentState(far));assertEquals(0,consumed(far));
                    assertEquals(far.due,jdbc.queryForObject("SELECT auto_confirm_at FROM shipments WHERE id=?",LocalDateTime.class,far.shipment));
                    unchangedMoney(fixture);snapshot("rollback-far owner=old-Rabbit-volume-and-DB-reservation",far);
                    String queues=command("exec","-T","rabbit-fault","rabbitmqctl","list_queues","name","messages_ready","messages_unacknowledged");
                    assertTrue(queues.contains("shop.shipment.confirm.delay"));
                    drainOwnedDelayFixtures();
                }
            } finally {clearEvidenceRows();}
        }
    }

    void rabbitSend(Sample sample,boolean delayed) throws Exception {
        var factory=new com.rabbitmq.client.ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(25672);
        factory.setUsername("rocket-test");factory.setPassword("rocket-test");
        try(var connection=factory.newConnection();var channel=connection.createChannel()) {
            channel.confirmSelect();
            var properties=new com.rabbitmq.client.AMQP.BasicProperties.Builder().contentType("text/plain").contentEncoding("UTF-8").deliveryMode(2);
            if(delayed) properties.expiration(Long.toString(Math.max(1,Duration.between(LocalDateTime.now(),sample.due).toMillis())));
            channel.basicPublish("shop.shipment",delayed?"shipment.confirm.delay":"shipment.confirm",true,properties.build(),Long.toString(sample.event).getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(5000);
        }
    }

    @Test void hRabbitAndRocketContendForTheSameUnconsumedEvent() throws Exception {
        try(var app=new RocketFaultApp("cross-channel-race",true,true,false,false,false);
            var fixture=new CommerceFixture(jdbc,RocketFaultApp.BASE)) {
            try {
                var sample=ship(fixture,LocalDateTime.now().minusSeconds(1));String rocketMessage;
                try(var lock=jdbc.getDataSource().getConnection()) {
                    lock.setAutoCommit(false);
                    try(var statement=lock.prepareStatement("SELECT id FROM orders WHERE id=? FOR UPDATE")) {
                        statement.setLong(1,sample.order);try(var row=statement.executeQuery()){assertTrue(row.next());}
                    }
                    rabbitSend(sample,false);rocketMessage=send(sample);
                    String waits="SELECT w.REQUESTING_ENGINE_TRANSACTION_ID,d.OBJECT_NAME,d.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks d ON d.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE d.OBJECT_SCHEMA='shiwushop_rocket' AND d.OBJECT_NAME IN ('orders','consumed_events')";
                    await(20,()->jdbc.queryForList(waits).stream().map(row->row.get("REQUESTING_ENGINE_TRANSACTION_ID")).distinct().count()>=2);
                    Files.writeString(RocketFaultApp.EVIDENCE.resolve("cross-channel-lock-waits.log"),Instant.now()+" eventId="+sample.event+" orderId="+sample.order+" "+jdbc.queryForList(waits)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    snapshot("both-transports-blocked-before-commit",sample);lock.commit();
                }
                await(30,()->read(app.log).contains("shipment_commit transport=rocket eventId="+sample.event+" messageId="+rocketMessage));
                once(sample);unchangedMoney(fixture);snapshot("cross-channel-race-complete",sample);
            } finally {clearEvidenceRows();}
        }
    }

    void drainOwnedDelayFixtures() throws Exception {
        var factory=new com.rabbitmq.client.ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(25672);
        factory.setUsername("rocket-test");factory.setPassword("rocket-test");
        try(var connection=factory.newConnection();var channel=connection.createChannel()) {
            com.rabbitmq.client.GetResponse message;
            while((message=channel.basicGet("shop.shipment.confirm.delay",false))!=null) {
                long id=Long.parseLong(new String(message.getBody(),StandardCharsets.UTF_8));
                var owned=samples.stream().filter(s->s.event==id).findFirst();
                if(owned.isEmpty()) {channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);fail("unexpected foreign fixture "+id);}
                snapshot("fixture-cleanup operator=local-acceptance reason=test-finished original-copy-evidenced",owned.orElseThrow());
                channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
            }
        }
    }

    void cleanupCancelledRabbitCopies() throws Exception {
        var known=new HashSet<Long>();
        for(String line:Files.readAllLines(RocketFaultApp.EVIDENCE.resolve("states.log"))) {
            if(!line.contains("inventory owner="))continue;
            var match=java.util.regex.Pattern.compile("eventId=(\\d+)").matcher(line);
            if(match.find())known.add(Long.parseLong(match.group(1)));
        }
        var factory=new com.rabbitmq.client.ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(25672);
        factory.setUsername("rocket-test");factory.setPassword("rocket-test");
        try(var connection=factory.newConnection();var channel=connection.createChannel()) {
            for(String queue:List.of("shop.shipment.confirm.delay","shop.shipment.confirm.dead")) {
                com.rabbitmq.client.GetResponse message;
                while((message=channel.basicGet(queue,false))!=null) {
                    long id=Long.parseLong(new String(message.getBody(),StandardCharsets.UTF_8));
                    if(!known.contains(id)||jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id=?",Integer.class,id)!=0) {
                        channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);fail("unowned Rabbit fixture "+id);
                    }
                    Files.writeString(RocketFaultApp.EVIDENCE.resolve("cancelled-fixtures.log"),Instant.now()+" queue="+queue+" eventId="+id+" prior-test-inventory-and-missing-row-confirmed\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
                }
            }
        }
    }

    Sample ship(CommerceFixture fixture,LocalDateTime due) throws Exception {
        long order=fixture.order("fault-"+UUID.randomUUID());
        jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?",order);
        var result=fixture.merchantClient.post("/merchant/shops/"+fixture.catalog.shop+"/orders/"+order+"/shipment",Map.of("carrierName","顺丰","trackingNo","SF-FAULT"));
        assertEquals(200,result.statusCode(),result.body());
        long shipment=jdbc.queryForObject("SELECT id FROM shipments WHERE order_id=?",Long.class,order);
        var original=jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE id=?",shipment);
        assertEquals(((LocalDateTime)original.get("shipped_at")).plusDays(10),original.get("auto_confirm_at"));
        long event=jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",Long.class,shipment);
        due=due.withNano(0);
        jdbc.update("UPDATE shipments SET shipped_at=?,auto_confirm_at=? WHERE id=?",due.minusDays(10),due,shipment);
        var sample=new Sample(event,shipment,order,due);samples.add(sample);return sample;
    }

    void eligible(Sample sample) {jdbc.update("UPDATE outbox_events SET next_attempt_at=NOW() WHERE id=?",sample.event);}
    String state(Sample s) {return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,s.event);}
    String shipmentState(Sample s) {return jdbc.queryForObject("SELECT status FROM shipments WHERE id=?",String.class,s.shipment);}
    int consumed(Sample s) {return jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",Integer.class,s.event);}
    int audits(Sample s) {return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",Integer.class,s.shipment);}
    void once(Sample s) {
        assertEquals("CONFIRMED",shipmentState(s));assertEquals(1,consumed(s));assertEquals(1,audits(s));
        assertEquals(s.due,jdbc.queryForObject("SELECT auto_confirm_at FROM shipments WHERE id=?",LocalDateTime.class,s.shipment));
    }
    void awaitPublishedDelivery(RocketFaultApp app,Sample sample) throws Exception {
        await(30,()->{
            String log=read(app.log);
            var match=java.util.regex.Pattern.compile("shipment_event outcome=published transport=rocket eventId="+sample.event+" shipmentId="+sample.shipment+" messageId=(\\S+)").matcher(log);
            String last=null;while(match.find())last=match.group(1);
            return last!=null && log.contains("shipment_commit transport=rocket eventId="+sample.event+" messageId="+last);
        });
    }
    void unchangedMoney(CommerceFixture fixture) {
        for(var s:samples) {
            var row=jdbc.queryForMap("SELECT total_amount,status FROM orders WHERE id=?",s.order);
            assertEquals(new java.math.BigDecimal("19.90"),row.get("total_amount"));assertEquals("PAID",row.get("status"));
        }
        assertEquals(20-samples.size(),jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
        assertEquals(samples.size(),jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?",Integer.class,fixture.sku));
    }
    void snapshot(String phase,Sample s) throws Exception {
        Files.writeString(RocketFaultApp.EVIDENCE.resolve("states.log"),Instant.now()+" "+phase+" eventId="+s.event+" shipmentId="+s.shipment+" D="+s.due+" outbox="+state(s)+" shipment="+shipmentState(s)+" consumed="+consumed(s)+" audit="+audits(s)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    void clearEvidenceRows() {
        for(var s:samples) {
            jdbc.update("DELETE FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",s.event);
            jdbc.update("DELETE FROM audit_events WHERE target_type='SHIPMENT' AND target_id=?",s.shipment);
        }
    }
    String send(Sample s) throws Exception {
        var provider=ClientServiceProvider.loadService();
        try(var producer=provider.newProducerBuilder().setClientConfiguration(ClientConfiguration.newBuilder()
                .setEndpoints("localhost:28081").enableSsl(false).build()).setTopics(ShipmentRocketConfig.TOPIC).setMaxAttempts(1).build()) {
            return producer.send(provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag(ShipmentRocketConfig.TAG)
                    .setKeys(Long.toString(s.event)).setBody(Long.toString(s.event).getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(System.currentTimeMillis()+2000).build()).getMessageId().toString();
        }
    }
    static String mysql(String sql) throws Exception {
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        String script=sql.contains("CREATE TRIGGER")
                ?sql.replace("USE shiwushop_rocket; ","USE shiwushop_rocket;\nDELIMITER $$\n")+"$$\n"
                :sql+";\n";
        String id=UUID.randomUUID().toString();
        Path input=RocketFaultApp.EVIDENCE.resolve("mysql-"+id+".sql"), output=RocketFaultApp.EVIDENCE.resolve("mysql-"+id+".log");
        Files.writeString(input,script);
        var p=new ProcessBuilder("docker","compose","-f","deploy/rocketmq/compose.yml","exec","-T","mysql","mysql","-uroot","-procket-test-root","-N","-B")
                .redirectInput(input.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("SQL command timeout: "+input);}
        String result=Files.readString(output);assertEquals(0,p.exitValue(),result);
        return result.lines().filter(s->!s.contains("Using a password on the command line")).collect(java.util.stream.Collectors.joining("\n"));
    }
    static String admin(String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("exec","-T","-e","JAVA_OPT_EXT=-Xms64m -Xmx128m","broker","sh","/opt/rocketmq/bin/mqadmin"));
        command.addAll(List.of(args));command.addAll(List.of("-n","namesrv:9876"));return command(command.toArray(String[]::new));
    }
    static void retryPolicy(String mode) throws Exception {
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        Path output=RocketFaultApp.EVIDENCE.resolve("retry-"+mode+"-"+System.currentTimeMillis()+".log");
        var p=new ProcessBuilder("pwsh","-NoProfile","-File","deploy/rocketmq/set-fault-retry.ps1","-Mode",mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!p.waitFor(30,TimeUnit.SECONDS)){p.destroyForcibly();fail("retry policy command timeout");}
        assertEquals(0,p.exitValue(),Files.readString(output));
    }
    static String command(String... args) throws Exception {
        List<String> cmd=new ArrayList<>(List.of("docker","compose","-f","deploy/rocketmq/compose.yml"));cmd.addAll(List.of(args));
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        Path output=RocketFaultApp.EVIDENCE.resolve("command-"+UUID.randomUUID()+".log");
        var p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!p.waitFor(45,TimeUnit.SECONDS)) {p.destroyForcibly();fail("Docker command timeout: "+output);}
        String result=Files.readString(output);
        Files.writeString(RocketFaultApp.EVIDENCE.resolve("commands.log"),Instant.now()+" "+Arrays.toString(args)+"\n"+result+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        assertEquals(0,p.exitValue(),result);
        assertFalse(result.contains("SubCommandException"),result);
        return result.lines().filter(s->!s.contains("Using a password on the command line")).collect(java.util.stream.Collectors.joining("\n"));
    }
    static void await(int seconds,BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(System.nanoTime()<end) {if(condition.getAsBoolean())return;Thread.sleep(100);}
        assertTrue(condition.getAsBoolean(),"condition not met in "+seconds+" seconds");
    }
    static String read(Path path) {try{return Files.readString(path);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}}
}
