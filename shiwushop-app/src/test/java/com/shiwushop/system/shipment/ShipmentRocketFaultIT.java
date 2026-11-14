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
    final org.junit.jupiter.api.extension.TestWatcher outcomes = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context, Throwable failure) {
            System.out.println("FAULT_FAILED " + context.getDisplayName());
            failure.printStackTrace(System.out);
        }

        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            System.out.println("FAULT_PASSED " + context.getDisplayName());
        }
    };
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai", "shiwushop",
            "test-shiwushop"));
    final List<Sample> samples = new ArrayList<>();

    record Sample(long event, long shipment, long order, LocalDateTime due) {
    }

    static final String PUBLISHER = "shiwushop-core/src/main/java/com/shiwushop/trade/shipment/ShipmentOutboxPublisher.java";
    static final String CONFIG = "shiwushop-core/src/main/java/com/shiwushop/trade/shipment/ShipmentRocketConfig.java";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPublisherKilledBeforeSendOrAfterReceipt(boolean afterReceipt) throws Exception {
        try (var app = new RocketFaultApp("kill-publisher-" + afterReceipt, true, true, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().plusSeconds(15));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentOutboxPublisher", PUBLISHER,
                        afterReceipt ? "if (outbox.markPublished(" : "var receipt = shipmentRocketTemplate.syncSendDeliverTimeMills");
                eligible(sample);
                app.awaitBreak();
                assertEquals("PENDING", state(sample));
                assertNotNull(jdbc.queryForObject("SELECT claim_token FROM outbox_events WHERE id=?", String.class,
                        sample.event));
                snapshot("publisher-paused-" + afterReceipt, sample);
                if (afterReceipt)
                    assertTrue(Files.readString(app.log)
                            .contains("shipment_send transport=rocket eventId=" + sample.event));
                app.kill();
                try (var recovery = new RocketFaultApp("recover-publisher-" + afterReceipt, true, true, false)) {
                    await(100, () -> state(sample).equals("PUBLISHED") && consumed(sample) == 1);
                    awaitPublishedDelivery(recovery, sample);
                    once(sample);
                    unchangedMoney(fixture);
                    snapshot("publisher-recovered-" + afterReceipt, sample);
                }
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void bRealCommitFailureRollsBackThenRedelivers() throws Exception {
        try (var app = new RocketFaultApp("commit-failure", true, false, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentRocketConfig", CONFIG, "consumer.handle(eventId);");
                String message = send(sample);
                var entered = app.awaitBreak();
                var consumerHit = (com.sun.jdi.event.BreakpointEvent) entered.iterator().next();
                // 只暂停本次消息的消费事务，避免命中其他定时任务的提交。
                app.breakOnCommit(consumerHit.thread());
                entered.resume();
                var paused = app.awaitBreak();
                String connections = mysql(
                        "SELECT trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_rows_modified>0").trim();
                assertTrue(connections.matches("[0-9]+"), "must have exactly one owned transaction: " + connections);
                mysql("KILL CONNECTION " + connections);
                app.breakAt("com.shiwushop.trade.shipment.ShipmentRocketConfig", CONFIG, "consumer.handle(eventId);");
                paused.resume();
                var retry = app.awaitBreak();
                assertEquals(0, consumed(sample));
                assertEquals(0, audits(sample));
                assertEquals("SHIPPED", shipmentState(sample));
                assertTrue(Files.readString(app.log).contains("commit"));
                snapshot("real-commit-failed messageId=" + message, sample);
                retry.resume();
                await(30, () -> consumed(sample) == 1);
                once(sample);
                unchangedMoney(fixture);
                snapshot("real-commit-recovered", sample);
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void bSendReplyLostAfterBrokerAccepts() throws Exception {
        try (var relay = new com.shiwushop.support.RocketReplyRelay();
             var app = new RocketFaultApp("send-reply-lost", true, true, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().plusSeconds(60));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentOutboxPublisher", PUBLISHER,
                        "var receipt = shipmentRocketTemplate.syncSendDeliverTimeMills");
                eligible(sample);
                var paused = app.awaitBreak();
                // 仅在被调试的测试进程中启用 VIP 端口（29911-2），让真实发送经过 TCP 故障夹具。
                var hit = (com.sun.jdi.event.BreakpointEvent) paused.iterator().next();
                var publisher = hit.thread().frame(0).thisObject();
                var template = (com.sun.jdi.ObjectReference) publisher.getValue(
                        publisher.referenceType().fieldByName("shipmentRocketTemplate"));
                var producer = (com.sun.jdi.ObjectReference) template.getValue(
                        template.referenceType().fieldByName("producer"));
                producer.setValue(producer.referenceType().fieldByName("vipChannelEnabled"), app.vm.mirrorOf(true));
                relay.loseReplies();
                paused.resume();
                await(12, () -> jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?", Integer.class,
                        sample.event) > 0);
                assertEquals("PENDING", state(sample));
                // 尚未到期的已接收消息索引位于 Broker 定时存储 Topic。
                String accepted = admin("queryMsgByKey", "-t", "rmq_sys_wheel_timer", "-k",
                        Long.toString(sample.event));
                var indexed = java.util.regex.Pattern.compile(
                                "(?m)^(\\S+)\\s+\\d+\\s+\\d+\\s+.*@rmq_sys_wheel_timer@K@" + sample.event + "@.*$")
                        .matcher(accepted);
                assertTrue(indexed.find(), accepted);
                snapshot("broker-accepted-reply-lost messageId=" + indexed.group(1), sample);
                relay.reconnect();
                await(100, () -> state(sample).equals("PUBLISHED") && consumed(sample) == 1);
                awaitPublishedDelivery(app, sample);
                once(sample);
                unchangedMoney(fixture);
                snapshot("unknown-send-recovered", sample);
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void cCommitThenProcessDiesBeforeAck() throws Exception {
        try (var app = new RocketFaultApp("ack-lost", true, false, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                app.breakAt("com.shiwushop.trade.shipment.ShipmentRocketConfig", CONFIG, "log.info(\"shipment_commit");
                String message = send(sample);
                app.awaitBreak();
                once(sample);
                snapshot("before-ack-kill messageId=" + message, sample);
                app.kill();
                try (var recovery = new RocketFaultApp("ack-redelivery", true, false, false)) {
                    await(150, () -> read(recovery.log).contains(
                            "shipment_commit transport=rocket eventId=" + sample.event + " messageId=" + message));
                    // Remoting 未提交消费位点的重投可保持 attempt=1；以跨进程同消息与一次审计验证。
                    assertTrue(read(app.log).contains("shipment_receive transport=rocket messageId=" + message));
                    assertTrue(read(recovery.log).contains("shipment_receive transport=rocket messageId=" + message));
                    once(sample);
                    unchangedMoney(fixture);
                    snapshot("ack-redelivered-same-message", sample);
                }
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void dMarkPublishedDatabaseFailureRetainsOriginalEvent() throws Exception {
        try (var app = new RocketFaultApp("mark-published-failure", true, true, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_mark_failure BEFORE UPDATE ON outbox_events FOR EACH ROW BEGIN IF NEW.id=" +
                        sample.event +
                        " AND NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated markPublished failure'; END IF; END");
                try {
                    eligible(sample);
                    await(65, () -> jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?", Integer.class,
                            sample.event) > 0);
                    assertEquals("PENDING", state(sample));
                    snapshot("mark-published-db-failed", sample);
                } finally {
                    mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_mark_failure");
                }
                await(65, () -> state(sample).equals("PUBLISHED") && consumed(sample) == 1);
                awaitPublishedDelivery(app, sample);
                once(sample);
                unchangedMoney(fixture);
                snapshot("mark-published-recovered", sample);
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void eBusinessDlqOriginalEventReplay() throws Exception {
        retryPolicy("fast");
        try (var app = new RocketFaultApp("business-dlq", true, false, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_audit_failure BEFORE INSERT ON audit_events FOR EACH ROW BEGIN IF NEW.target_id=" +
                        sample.shipment +
                        " AND NEW.action='SHIPMENT_CONFIRMED_BY_SYSTEM' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated business audit unavailable'; END IF; END");
                String message;
                long sentAt = System.currentTimeMillis();
                try {
                    message = send(sample);
                    await(100, () -> read(app.log).contains("messageId=" + message + " attempt=16"));
                    var delivered = java.util.regex.Pattern.compile(
                                    "shipment_receive transport=rocket messageId=" + message + " attempt=(\\d+)")
                            .matcher(read(app.log)).results().map(m -> Integer.parseInt(m.group(1))).toList();
                    assertEquals(java.util.stream.IntStream.rangeClosed(1, 16).boxed().toList(), delivered);
                    String dlq = "";
                    for (int i = 0; i < 6; i++) {
                        dlq = admin("consumeMessage", "-t", "%DLQ%" + ShipmentRocketConfig.TOPIC, "-b",
                                "shipment-broker", "-i", "0", "-s", Long.toString(sentAt), "-c", "10000");
                        if (dlq.contains("BODY: " + sample.event))
                            break;
                        Thread.sleep(1000);
                    }
                    assertTrue(
                            java.util.regex.Pattern.compile("(?m)BODY: " + sample.event + "\\r?$").matcher(dlq).find(),
                            dlq);
                    assertTrue(dlq.contains("MSGID: " + message), dlq);
                    assertEquals(0, consumed(sample));
                    assertEquals(0, audits(sample));
                    snapshot("business-dlq messageId=" + message, sample);
                } finally {
                    mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_audit_failure");
                }
                String replay = send(sample);
                snapshot(
                        "manual-replay operator=local-acceptance reason=audit-store-restored sourceMessage=" + message +
                                " replayMessage=" + replay, sample);
                await(30, () -> consumed(sample) == 1);
                String duplicate = send(sample);
                await(30, () -> read(app.log).contains(
                        "shipment_commit transport=rocket eventId=" + sample.event + " messageId=" + duplicate));
                once(sample);
                unchangedMoney(fixture);
                snapshot("business-replay-complete", sample);
            } finally {
                clearEvidenceRows();
            }
        } finally {
            retryPolicy("restore");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"after-dedupe", "before-audit"})
    void dTransactionWriteFailureRollsBackAllRows(String stage) throws Exception {
        try (var app = new RocketFaultApp("transaction-" + stage, true, false, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                String target = stage.equals(
                        "after-dedupe") ? "BEFORE UPDATE ON shipments" : "BEFORE INSERT ON audit_events";
                String condition = stage.equals("after-dedupe") ?
                        "NEW.id=" + sample.shipment :
                        "NEW.target_id=" + sample.shipment + " AND NEW.action='SHIPMENT_CONFIRMED_BY_SYSTEM'";
                mysql("USE shiwushop_rocket; CREATE TRIGGER rocket_test_stage_failure " + target +
                        " FOR EACH ROW BEGIN IF " + condition +
                        " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated transaction stage failure'; END IF; END");
                try {
                    String message = send(sample);
                    await(20, () -> read(app.log).contains(
                            "shipment_consume_failed transport=rocket messageId=" + message));
                    assertEquals(0, consumed(sample));
                    assertEquals(0, audits(sample));
                    assertEquals("SHIPPED", shipmentState(sample));
                    snapshot("rolled-back stage=" + stage, sample);
                } finally {
                    mysql("USE shiwushop_rocket; DROP TRIGGER rocket_test_stage_failure");
                }
                await(100, () -> consumed(sample) == 1);
                once(sample);
                unchangedMoney(fixture);
                snapshot("retry-recovered stage=" + stage, sample);
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void fHundredCompensatedWhileMqDownForTwoMinutes() throws Exception {
        try (var app = new RocketFaultApp("compensation-100", true, true, true);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                jdbc.update("UPDATE skus SET stock=120 WHERE id=?", fixture.sku);
                for (int i = 0; i < 100; i++)
                    ship(fixture, LocalDateTime.now().plusMinutes(10));
                LocalDateTime due = LocalDateTime.now().plusSeconds(45).withNano(0);
                var refunded = ship(fixture, due);
                String tradeNo = UUID.randomUUID().toString();
                jdbc.update(
                        "INSERT INTO payment_attempts (order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) VALUES (?,'ALIPAY_SANDBOX',?,'test','test',19.90,'SUCCESS',NOW())",
                        refunded.order, tradeNo);
                long payment = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE out_trade_no=?", Long.class,
                        tradeNo);
                jdbc.update(
                        "INSERT INTO after_sale_cases (order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REFUNDED','isolated acceptance',19.90,NOW())",
                        refunded.order);
                long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class,
                        refunded.order);
                jdbc.update(
                        "INSERT INTO refund_attempts (case_id,payment_attempt_id,out_request_no,amount,status,requested_at,resolved_at) VALUES (?,?,?,19.90,'SUCCESS',NOW(),NOW())",
                        sale, payment, UUID.randomUUID().toString());
                var provider = ClientServiceProvider.loadService();
                try (var producer = provider.newProducerBuilder()
                        .setClientConfiguration(ClientConfiguration.newBuilder()
                                .setEndpoints("localhost:28081").enableSsl(false).build())
                        .setTopics(ShipmentRocketConfig.TOPIC).setMaxAttempts(1).build()) {
                    for (int i = 0; i < 100; i++) {
                        var old = samples.get(i);
                        var s = new Sample(old.event, old.shipment, old.order, due);
                        samples.set(i, s);
                        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?", due, s.shipment);
                        String state = new String[]{"PENDING", "PUBLISHED", "DEAD"}[i % 3];
                        if (state.equals("PUBLISHED")) {
                            var receipt = producer.send(
                                    provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC)
                                            .setTag(ShipmentRocketConfig.TAG)
                                            .setKeys(Long.toString(s.event))
                                            .setBody(Long.toString(s.event).getBytes(StandardCharsets.UTF_8))
                                            .setDeliveryTimestamp(
                                                    due.plusSeconds(2).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
                                                            .toEpochMilli()).build());
                            snapshot("accepted-before-outage messageId=" + receipt.getMessageId(), s);
                        }
                        jdbc.update("UPDATE outbox_events SET status=?,next_attempt_at=? WHERE id=?", state, due,
                                s.event);
                        snapshot("outage-initial state=" + state, s);
                    }
                }
                assertEquals(101, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id=s.order_id WHERE o.user_id=? AND s.status='SHIPPED'",
                        Integer.class, fixture.buyer.id()));
                command("stop", "-t", "0", "proxy", "broker");
                long stopped = System.nanoTime();
                Instant stoppedAt = Instant.now();
                try {
                    await(180, () -> jdbc.queryForObject(
                            "SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id=s.order_id WHERE o.user_id=? AND s.status='CONFIRMED'",
                            Integer.class, fixture.buyer.id()) == 100);
                    long elapsed = Duration.ofNanos(System.nanoTime() - stopped).toMillis();
                    for (var s : samples.subList(0, 100)) {
                        assertEquals(1, audits(s));
                        assertEquals(0, consumed(s));
                        snapshot("scan-only elapsedMs=" + elapsed, s);
                    }
                    assertEquals("SHIPPED", shipmentState(refunded));
                    assertEquals(0, audits(refunded));
                    assertTrue(elapsed <= 180000);
                    while (System.nanoTime() - stopped < Duration.ofMinutes(2).toNanos())
                        Thread.sleep(500);
                    Files.writeString(RocketFaultApp.EVIDENCE.resolve("outage-timing.log"),
                            "stoppedAt=" + stoppedAt + " restartRequestedAt=" + Instant.now() + " confirmedDownMs=" +
                                    Duration.ofNanos(System.nanoTime() - stopped).toMillis() + "\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } finally {
                    command("start", "broker", "proxy");
                }
                await(180, () -> java.util.stream.IntStream.range(0, 100).filter(i -> i % 3 != 2)
                        .allMatch(i -> state(samples.get(i)).equals("PUBLISHED") && consumed(samples.get(i)) == 1));
                for (var s : samples.subList(0, 100)) {
                    assertEquals(1, audits(s));
                    assertEquals(s.due,
                            jdbc.queryForObject("SELECT auto_confirm_at FROM shipments WHERE id=?", LocalDateTime.class,
                                    s.shipment));
                    snapshot("after-mq-recovery", s);
                }
                assertEquals("SHIPPED", shipmentState(refunded));
                assertEquals(0, audits(refunded));
                snapshot("refund-still-excluded", refunded);
                assertEquals("SUCCESS",
                        jdbc.queryForObject("SELECT status FROM refund_attempts WHERE case_id=?", String.class, sale));
                assertEquals(new java.math.BigDecimal("19.90"),
                        jdbc.queryForObject("SELECT amount FROM refund_attempts WHERE case_id=?",
                                java.math.BigDecimal.class, sale));
                for (var s : samples) {
                    assertEquals("PAID",
                            jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, s.order));
                    assertEquals(new java.math.BigDecimal("19.90"),
                            jdbc.queryForObject("SELECT total_amount FROM orders WHERE id=?",
                                    java.math.BigDecimal.class, s.order));
                }
                assertEquals(19, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
                assertEquals(101,
                        jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?", Integer.class,
                                fixture.sku));
            } finally {
                clearEvidenceRows();
            }
        }
    }

    @Test
    void gDuplicateRocketDeliveriesKeepOneAudit() throws Exception {
        try (var app = new RocketFaultApp("duplicate-delivery", true, false, false);
             var fixture = new CommerceFixture(jdbc, RocketFaultApp.BASE)) {
            try {
                var sample = ship(fixture, LocalDateTime.now().minusSeconds(1));
                String first = send(sample), second = send(sample);
                await(30, () -> read(app.log).contains(
                        "shipment_commit transport=rocket eventId=" + sample.event + " messageId=" + first)
                        && read(app.log).contains(
                        "shipment_commit transport=rocket eventId=" + sample.event + " messageId=" + second));
                once(sample);
                unchangedMoney(fixture);
                snapshot("duplicate-deliveries-complete", sample);
            } finally {
                clearEvidenceRows();
            }
        }
    }

    Sample ship(CommerceFixture fixture, LocalDateTime due) throws Exception {
        long order = fixture.order("fault-" + UUID.randomUUID());
        jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?", order);
        var result = fixture.merchantClient.post(
                "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment",
                Map.of("carrierName", "顺丰", "trackingNo", "SF-FAULT"));
        assertEquals(200, result.statusCode(), result.body());
        long shipment = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id=?", Long.class, order);
        var signed = fixture.merchantClient.post(
                "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order
                        + "/shipments/" + shipment + "/simulate-tracking", Map.of("step", "SIGNED"));
        assertEquals(200, signed.statusCode(), signed.body());
        var original = jdbc.queryForMap("SELECT carrier_signed_at,auto_confirm_at FROM shipments WHERE id=?", shipment);
        assertEquals(((LocalDateTime) original.get("carrier_signed_at")).plusDays(10), original.get("auto_confirm_at"));
        long event = jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?", Long.class,
                shipment);
        due = due.withNano(0);
        jdbc.update("UPDATE shipments SET shipped_at=?,carrier_signed_at=?,auto_confirm_at=? WHERE id=?",
                due.minusDays(11), due.minusDays(10), due, shipment);
        var sample = new Sample(event, shipment, order, due);
        samples.add(sample);
        return sample;
    }

    void eligible(Sample sample) {
        jdbc.update("UPDATE outbox_events SET next_attempt_at=NOW() WHERE id=?", sample.event);
    }

    String state(Sample s) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, s.event);
    }

    String shipmentState(Sample s) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE id=?", String.class, s.shipment);
    }

    int consumed(Sample s) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, s.event);
    }

    int audits(Sample s) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",
                Integer.class, s.shipment);
    }

    void once(Sample s) {
        assertEquals("CONFIRMED", shipmentState(s));
        assertEquals(1, consumed(s));
        assertEquals(1, audits(s));
        assertEquals(s.due, jdbc.queryForObject("SELECT auto_confirm_at FROM shipments WHERE id=?", LocalDateTime.class,
                s.shipment));
    }

    void awaitPublishedDelivery(RocketFaultApp app, Sample sample) throws Exception {
        await(30, () -> {
            String log = read(app.log);
            var match = java.util.regex.Pattern.compile(
                    "shipment_event outcome=published transport=rocket eventId=" + sample.event + " shipmentId=" +
                            sample.shipment + " messageId=(\\S+)").matcher(log);
            String last = null;
            while (match.find())
                last = match.group(1);
            return last != null &&
                    log.contains("shipment_commit transport=rocket eventId=" + sample.event + " messageId=" + last);
        });
    }

    void unchangedMoney(CommerceFixture fixture) {
        for (var s : samples) {
            var row = jdbc.queryForMap("SELECT total_amount,status FROM orders WHERE id=?", s.order);
            assertEquals(new java.math.BigDecimal("19.90"), row.get("total_amount"));
            assertEquals("PAID", row.get("status"));
        }
        assertEquals(20 - samples.size(),
                jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(samples.size(),
                jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?", Integer.class,
                        fixture.sku));
    }

    void snapshot(String phase, Sample s) throws Exception {
        Files.writeString(RocketFaultApp.EVIDENCE.resolve("states.log"),
                Instant.now() + " " + phase + " eventId=" + s.event + " shipmentId=" + s.shipment + " D=" + s.due +
                        " outbox=" + state(s) + " shipment=" + shipmentState(s) + " consumed=" + consumed(s) +
                        " audit=" + audits(s) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    void clearEvidenceRows() {
        for (var s : samples) {
            jdbc.update("DELETE FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                    s.event);
            jdbc.update("DELETE FROM audit_events WHERE target_type='SHIPMENT' AND target_id=?", s.shipment);
        }
    }

    String send(Sample s) throws Exception {
        var provider = ClientServiceProvider.loadService();
        try (var producer = provider.newProducerBuilder().setClientConfiguration(ClientConfiguration.newBuilder()
                        .setEndpoints("localhost:28081").enableSsl(false).build()).setTopics(ShipmentRocketConfig.TOPIC)
                .setMaxAttempts(1).build()) {
            return producer.send(
                    provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag(ShipmentRocketConfig.TAG)
                            .setKeys(Long.toString(s.event))
                            .setBody(Long.toString(s.event).getBytes(StandardCharsets.UTF_8))
                            .setDeliveryTimestamp(System.currentTimeMillis() + 2000).build()).getMessageId().toString();
        }
    }

    static String mysql(String sql) throws Exception {
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        String script = sql.contains("CREATE TRIGGER")
                ? sql.replace("USE shiwushop_rocket; ", "USE shiwushop_rocket;\nDELIMITER $$\n") + "$$\n"
                : sql + ";\n";
        String id = UUID.randomUUID().toString();
        Path input = RocketFaultApp.EVIDENCE.resolve("mysql-" + id + ".sql"), output = RocketFaultApp.EVIDENCE.resolve(
                "mysql-" + id + ".log");
        Files.writeString(input, script);
        var p = new ProcessBuilder("docker", "compose", "-f", "deploy/rocketmq/compose.yml", "exec", "-T", "mysql",
                "mysql", "-uroot", "-procket-test-root", "-N", "-B")
                .redirectInput(input.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!p.waitFor(150, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("SQL command timeout: " + input);
        }
        String result = Files.readString(output);
        assertEquals(0, p.exitValue(), result);
        return result.lines().filter(s -> !s.contains("Using a password on the command line"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static String admin(String... args) throws Exception {
        List<String> command = new ArrayList<>(
                List.of("exec", "-T", "-e", "JAVA_OPT_EXT=-Xms64m -Xmx128m", "broker", "sh",
                        "/opt/rocketmq/bin/mqadmin"));
        command.addAll(List.of(args));
        command.addAll(List.of("-n", "namesrv:9876"));
        return command(command.toArray(String[]::new));
    }

    static void retryPolicy(String mode) throws Exception {
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        Path output = RocketFaultApp.EVIDENCE.resolve("retry-" + mode + "-" + System.currentTimeMillis() + ".log");
        var p = new ProcessBuilder("pwsh", "-NoProfile", "-File", "deploy/rocketmq/set-fault-retry.ps1", "-Mode", mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("retry policy command timeout");
        }
        assertEquals(0, p.exitValue(), Files.readString(output));
    }

    static String command(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", "deploy/rocketmq/compose.yml"));
        cmd.addAll(List.of(args));
        Files.createDirectories(RocketFaultApp.EVIDENCE);
        Path output = RocketFaultApp.EVIDENCE.resolve("command-" + UUID.randomUUID() + ".log");
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!p.waitFor(45, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("Docker command timeout: " + output);
        }
        String result = Files.readString(output);
        Files.writeString(RocketFaultApp.EVIDENCE.resolve("commands.log"),
                Instant.now() + " " + Arrays.toString(args) + "\n" + result + "\n", StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        assertEquals(0, p.exitValue(), result);
        assertFalse(result.contains("SubCommandException"), result);
        return result.lines().filter(s -> !s.contains("Using a password on the command line"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static void await(int seconds, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condition not met in " + seconds + " seconds");
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
