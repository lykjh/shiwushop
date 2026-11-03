package com.shiwushop.integration.component.shipment;

import com.shiwushop.trade.shipment.*;
import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.ShipmentService;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

// 强制集成入口：无条件跳过；必须显式 -Dtest=ShipmentRocketLiveIT，环境缺失即失败。
@SpringBootTest(properties={
    "shop.shipment.rocket-enabled=true", "shop.shipment.rabbit-enabled=false",
    "shop.shipment.rocket-endpoints=localhost:28081", "shop.shipment.expiry-enabled=false",
    "shop.shipment.scan-delay-ms=600000", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class ShipmentRocketLiveIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentService shipments;
    @Autowired ShipmentMapper shipmentMapper;
    @Autowired ShipmentOutboxPublisher publisher;
    @Autowired ShipmentConfirmConsumer consumer;
    @Autowired Producer producer;
    @Autowired Clock clock;
    @Autowired ApplicationContext context;
    @Autowired ShipmentRocketHealthIndicator health;
    @Autowired com.shiwushop.common.outbox.OutboxEventMapper outbox;
    private Long merchantId, buyerId, shopId, orderId;

    @BeforeEach void isolation() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:23306/shiwushop_rocket"));
        }
        assertTrue(context.getBeansOfType(ShipmentExpiryJob.class).isEmpty(), "MQ 证明必须关闭扫描");
    }

    @Test void tenDayReservationAndEarlyDeliveryRollback() {
        createShipmentFixture();
        var shipment=shipmentMapper.selectByOrderId(orderId); long event=eventId(shipment.getId());
        assertEquals(shipment.getShippedAt().plusDays(10), shipment.getAutoConfirmAt());
        assertEquals(shipment.getAutoConfirmAt().minusHours(23), jdbc.queryForObject("SELECT next_attempt_at FROM outbox_events WHERE id=?", LocalDateTime.class,event));
        publisher.publishPending();
        assertEquals(0,jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?",Integer.class,event));
        assertThrows(IllegalStateException.class,()->consumer.handle(Long.toString(event)));
        assertEquals(0,consumed(event));
        jdbc.update("UPDATE shipments SET shipped_at=DATE_SUB(NOW(),INTERVAL 10 DAY), auto_confirm_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",shipment.getId());
        consumer.handle(Long.toString(event)); consumer.handle(Long.toString(event));
        assertEquals(1,consumed(event));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",Integer.class,shipment.getId()));
    }

    @Test void applicationLoggingKeepsFileRollingAndMdc() {
        var logging=(ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
        var file=assertInstanceOf(ch.qos.logback.core.rolling.RollingFileAppender.class,
                logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE"));
        assertTrue(file.getFile().endsWith("shiwushop.log"));
        var encoder=assertInstanceOf(ch.qos.logback.classic.encoder.PatternLayoutEncoder.class,file.getEncoder());
        assertTrue(encoder.getPattern().contains("eventId"));
        var rolling=assertInstanceOf(ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy.class,file.getRollingPolicy());
        assertEquals(14,rolling.getMaxHistory());
    }

    @Test void healthUsesRealConsumerHeartbeat() {
        assertEquals(org.springframework.boot.health.contributor.Status.UP,health.health().getStatus());
    }

    @Test void retryBackoffIsNotCountedAsFutureReservation() {
        var now=LocalDateTime.now(clock);
        long before=outbox.futureShipmentCount(now);
        createShipmentFixture();
        var shipment=shipmentMapper.selectByOrderId(orderId);
        assertEquals(before+1,outbox.futureShipmentCount(now));
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",now.plusMinutes(5),shipment.getId());
        jdbc.update("UPDATE outbox_events SET attempts=1,next_attempt_at=? WHERE id=?",now.plusMinutes(1),eventId(shipment.getId()));
        assertEquals(before,outbox.futureShipmentCount(now));
    }

    @Test void realMessageConfirmsOnlyAfterDeadlineWithScannerAbsent() throws Exception {
        createShipmentFixture();
        var shipment=shipmentMapper.selectByOrderId(orderId); long event=eventId(shipment.getId());
        LocalDateTime due=LocalDateTime.now(clock).plusSeconds(5).withNano(0);
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",due,shipment.getId());
        jdbc.update("UPDATE outbox_events SET next_attempt_at=NOW() WHERE id=?",event);
        publisher.publishPending();
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,event));
        long timeout=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(consumed(event)==0 && System.nanoTime()<timeout) Thread.sleep(100);
        assertEquals(1,consumed(event));
        var confirmed=shipmentMapper.selectById(shipment.getId());
        assertEquals("CONFIRMED",confirmed.getStatus().name());
        assertFalse(confirmed.getConfirmedAt().isBefore(due));
        assertEquals("SYSTEM",confirmed.getConfirmSource().name());
        System.out.printf("ROCKET_SAMPLE eventId=%d shipmentId=%d D=%s C=%s lateMs=%d%n",event,shipment.getId(),due,confirmed.getConfirmedAt(),Duration.between(due,confirmed.getConfirmedAt()).toMillis());
    }

    @Test void proxyRejectsBeyond24HoursAndAccepts23Hours() throws Exception {
        var provider=ClientServiceProvider.loadService();
        for(long delay:new long[]{86405,864000}) {
            var message=provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("LIMIT_PROBE")
                .setKeys("limit-"+UUID.randomUUID()).setBody("1".getBytes(StandardCharsets.UTF_8))
                .setDeliveryTimestamp(System.currentTimeMillis()+delay*1000).build();
            assertThrows(org.apache.rocketmq.client.apis.ClientException.class,()->producer.send(message));
        }
        var message=provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag("LIMIT_PROBE")
            .setKeys("23h-"+UUID.randomUUID()).setBody("1".getBytes(StandardCharsets.UTF_8))
            .setDeliveryTimestamp(System.currentTimeMillis()+23*3600*1000).build();
        assertNotNull(producer.send(message).getMessageId());
    }

    @Test void transactionFailureRollsBackBusinessAuditAndDedupe() {
        createShipmentFixture();
        var shipment=shipmentMapper.selectByOrderId(orderId); long event=eventId(shipment.getId());
        jdbc.update("UPDATE shipments SET shipped_at=DATE_SUB(NOW(),INTERVAL 10 DAY), auto_confirm_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",shipment.getId());
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{consumer.handle(Long.toString(event)); throw new IllegalStateException("injected before commit");}));
        assertEquals(0,consumed(event));
        assertEquals("SHIPPED",shipmentMapper.selectById(shipment.getId()).getStatus().name());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",Integer.class,shipment.getId()));
        consumer.handle(Long.toString(event)); assertEquals(1,consumed(event));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PENDING","PUBLISHED","DEAD"})
    void independentScannerCompensatesEveryOutboxState(String state) {
        createShipmentFixture();
        var shipment=shipmentMapper.selectByOrderId(orderId); long event=eventId(shipment.getId());
        jdbc.update("UPDATE shipments SET shipped_at=DATE_SUB(NOW(),INTERVAL 10 DAY), auto_confirm_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",shipment.getId());
        jdbc.update("UPDATE outbox_events SET status=? WHERE id=?",state,event);
        new ShipmentExpiryJob(shipments,shipmentMapper,clock).confirmDue();
        new ShipmentExpiryJob(shipments,shipmentMapper,clock).confirmDue();
        assertEquals("CONFIRMED",shipmentMapper.selectById(shipment.getId()).getStatus().name());
        assertEquals(0,consumed(event),"本项证明扫描，不冒充 MQ 消费");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",Integer.class,shipment.getId()));
    }

    @Test void invalidAndUnknownEventsCannotCreateDedupe() {
        for(String body:new String[]{"bad","0","-1","9223372036854775808","9223372036854775807"})
            assertThrows(IllegalArgumentException.class,()->consumer.handle(body));
    }

    private long eventId(long shipment) {return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",Long.class,shipment);}
    private int consumed(long event) {return jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",Integer.class,event);}
    private void createShipmentFixture() {
        merchantId = user("merchant");
        buyerId = user("buyer");
        jdbc.update("INSERT INTO user_accounts (user_id,username,password_hash,role,status) " +
                "VALUES (?,?,?,'MERCHANT','ACTIVE')", merchantId, "rocket_" + UUID.randomUUID(), "test-only");
        String shopName = "物流验收店铺-" + UUID.randomUUID();
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", merchantId, shopName);
        shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, shopName);
        jdbc.update("INSERT INTO orders (user_id,shop_id,total_amount,status,pay_expires_at,paid_at," +
                        "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) " +
                        "VALUES (?,?,?,'PAID',NOW(),NOW(),?,?,?,?,?,?,1)",
                buyerId, shopId, new BigDecimal("40.00"), new byte[]{1}, new byte[]{1},
                "北京市", "北京市", "海淀区", new byte[]{1});
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE shop_id=? AND user_id=?", Long.class, shopId, buyerId);

        shipments.ship(merchantId, shopId, orderId, new ShipmentCreateDTO("顺丰", "SF-ROCKET"));
    }

    @AfterEach
    void cleanUp() {
        if (orderId != null) {
            jdbc.update("DELETE FROM consumed_events WHERE event_id IN (SELECT id FROM outbox_events " +
                    "WHERE aggregate_type='SHIPMENT' AND aggregate_id IN (SELECT id FROM shipments WHERE order_id=?))", orderId);
            jdbc.update("DELETE FROM audit_events WHERE target_type='SHIPMENT' AND target_id IN " +
                    "(SELECT id FROM shipments WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id IN " +
                    "(SELECT id FROM shipments WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
        }
        if (shopId != null) jdbc.update("DELETE FROM shops WHERE id=?", shopId);
        if (merchantId != null) jdbc.update("DELETE FROM user_accounts WHERE user_id=?", merchantId);
        if (merchantId != null) jdbc.update("DELETE FROM users WHERE id=?", merchantId);
        if (buyerId != null) jdbc.update("DELETE FROM users WHERE id=?", buyerId);
    }

    private Long user(String kind) {
        String name = "rocket_" + kind + "_" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (nickname) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, name);
    }
}
