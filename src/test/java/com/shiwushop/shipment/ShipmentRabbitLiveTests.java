package com.shiwushop.shipment;

import com.shiwushop.commerce.shipment.ShipmentMessagingConfig;
import com.shiwushop.commerce.shipment.ShipmentOutboxPublisher;
import com.shiwushop.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.entity.status.ShipmentStatus;
import com.shiwushop.mapper.shipment.ShipmentMapper;
import com.shiwushop.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "shipment.rabbit.live", matches = "true")
@SpringBootTest(properties = {
        "shop.shipment.rabbit-enabled=true",
        "shop.shipment.scan-delay-ms=600000"
})
class ShipmentRabbitLiveTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShipmentService shipments;
    @Autowired private ShipmentMapper shipmentMapper;
    @Autowired private ShipmentOutboxPublisher publisher;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private Clock clock;

    private Long merchantId;
    private Long buyerId;
    private Long shopId;
    private Long orderId;

    @Test
    void brokerDeliversExpiredShipmentEventAndConfirmsOrder() throws Exception {
        createShipmentFixture();
        Long shipmentId = shipmentMapper.selectByOrderId(orderId).getId();
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?", LocalDateTime.now().plusSeconds(3), shipmentId);
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipmentId);
        long publishDeadline = System.nanoTime() + 10_000_000_000L;
        String publishStatus = "PENDING";
        while (System.nanoTime() < publishDeadline && !"PUBLISHED".equals(publishStatus)) {
            publisher.publishPending();
            publishStatus = jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, eventId);
            if (!"PUBLISHED".equals(publishStatus)) Thread.sleep(200);
        }
        assertEquals("PUBLISHED", publishStatus);

        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline && shipmentMapper.selectById(shipmentId).getStatus() != ShipmentStatus.CONFIRMED) {
            Thread.sleep(200);
        }
        assertEquals(ShipmentStatus.CONFIRMED, shipmentMapper.selectById(shipmentId).getStatus());
        assertEquals("SYSTEM", shipmentMapper.selectById(shipmentId).getConfirmSource().name());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
    }

    @Test
    @EnabledIfSystemProperty(named = "shipment.rabbit.fault.live", matches = "true")
    void rejectedMessageReachesDeadLetterQueue() throws Exception {
        requireIsolatedBroker();
        String invalidEventId = "invalid-" + UUID.randomUUID();
        rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, invalidEventId);
        Message dead = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && dead == null) {
            dead = rabbit.receive(ShipmentMessagingConfig.DEAD_QUEUE, 500);
        }
        assertNotNull(dead);
        assertEquals(invalidEventId, new String(dead.getBody(), StandardCharsets.UTF_8));
        assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
    }

    @Test
    @EnabledIfSystemProperty(named = "shipment.rabbit.manual-replay.live", matches = "true")
    void manuallyReplayedDeadLetterConfirmsOnce() throws Exception {
        if (!System.getenv().getOrDefault("SPRING_RABBITMQ_VIRTUAL_HOST", "").matches("accept_[a-f0-9]{10}")) {
            throw new IllegalStateException("人工重放测试必须使用专用临时 RabbitMQ vhost");
        }
        createShipmentFixture();
        Long shipmentId = shipmentMapper.selectByOrderId(orderId).getId();
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipmentId);
        LocalDateTime dueAt = shipmentMapper.selectById(shipmentId).getShippedAt();
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?", dueAt, shipmentId);
        try {
            jdbc.update("UPDATE outbox_events SET event_type='TEST_INVALID' WHERE id=?", eventId);
            rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
            Message dead = null;
            long deadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadDeadline && dead == null) {
                dead = rabbit.receive(ShipmentMessagingConfig.DEAD_QUEUE, 500);
            }
            assertNotNull(dead);
            assertEquals(eventId.toString(), new String(dead.getBody(), StandardCharsets.UTF_8));
            assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
        } finally {
            jdbc.update("UPDATE outbox_events SET event_type='SHIPMENT_AUTO_CONFIRM' WHERE id=?", eventId);
        }

        LocalDateTime storedDueAt = shipmentMapper.selectById(shipmentId).getAutoConfirmAt();
        long dueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (storedDueAt.isAfter(LocalDateTime.now(clock)) && System.nanoTime() < dueDeadline) {
            Thread.sleep(100);
        }
        assertTrue(!storedDueAt.isAfter(LocalDateTime.now(clock)),
                "storedDueAt=" + storedDueAt + ", businessNow=" + LocalDateTime.now(clock));
        rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
        long confirmDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < confirmDeadline && shipmentMapper.selectById(shipmentId).getStatus() != ShipmentStatus.CONFIRMED) {
            Thread.sleep(200);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
        assertEquals(ShipmentStatus.CONFIRMED, shipmentMapper.selectById(shipmentId).getStatus());
        rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
        Thread.sleep(1000);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
    }

    @Test
    @EnabledIfSystemProperty(named = "shipment.rabbit.backlog.live", matches = "true")
    void queuedMessagesSurviveBacklogAndConsumeOnlyOnce() throws Exception {
        if (!System.getenv().getOrDefault("SPRING_RABBITMQ_VIRTUAL_HOST", "").matches("accept_[a-f0-9]{10}")) {
            throw new IllegalStateException("积压测试必须使用专用临时 RabbitMQ vhost");
        }
        createShipmentFixture();
        Long shipmentId = shipmentMapper.selectByOrderId(orderId).getId();
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipmentId);
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",
                shipmentMapper.selectById(shipmentId).getShippedAt(), shipmentId);
        listeners.stop();
        try {
            for (int i = 0; i < 100; i++) {
                rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
            }
            assertEquals(100, workQueueDepth());
            Thread.sleep(120_000);
            assertEquals(100, workQueueDepth());
        } finally {
            listeners.start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline &&
                (shipmentMapper.selectById(shipmentId).getStatus() != ShipmentStatus.CONFIRMED ||
                        workQueueDepth() != 0)) {
            Thread.sleep(200);
        }
        assertEquals(ShipmentStatus.CONFIRMED, shipmentMapper.selectById(shipmentId).getStatus());
        assertEquals(0, workQueueDepth());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
    }

    @Test
    @EnabledIfSystemProperty(named = "shipment.rabbit.dual.live", matches = "true")
    void duplicateMessagesWithTwoConsumersConfirmOnce() throws Exception {
        if (!System.getenv().getOrDefault("SPRING_RABBITMQ_VIRTUAL_HOST", "").matches("accept_[a-f0-9]{10}")) {
            throw new IllegalStateException("双实例测试必须使用专用临时 RabbitMQ vhost");
        }
        createShipmentFixture();
        int consumers = rabbit.execute(channel -> channel.queueDeclarePassive(ShipmentMessagingConfig.WORK_QUEUE).getConsumerCount());
        assertTrue(consumers >= 2, "expected two application consumers, got " + consumers);
        Long shipmentId = shipmentMapper.selectByOrderId(orderId).getId();
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipmentId);
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",
                shipmentMapper.selectById(shipmentId).getShippedAt(), shipmentId);
        for (int i = 0; i < 50; i++) {
            rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
        }
        String marker = System.getProperty("shipment.rabbit.kill-marker", "");
        if (!marker.isBlank()) {
            Path path = Path.of(marker).toAbsolutePath().normalize();
            if (!path.startsWith(Path.of("target").toAbsolutePath().normalize())
                    || !path.getFileName().toString().startsWith("dual-peer-stop-")) {
                throw new IllegalStateException("故障信号文件必须位于 target 下");
            }
            Files.writeString(path, "stop test peer");
            long peerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < peerDeadline && workQueueConsumers() > 1) Thread.sleep(100);
            assertEquals(1, workQueueConsumers());
        }
        for (int i = 0; i < 50; i++) {
            rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline &&
                (shipmentMapper.selectById(shipmentId).getStatus() != ShipmentStatus.CONFIRMED || workQueueDepth() != 0)) {
            Thread.sleep(200);
        }
        assertEquals(ShipmentStatus.CONFIRMED, shipmentMapper.selectById(shipmentId).getStatus());
        assertEquals(0, workQueueDepth());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
    }

    private int workQueueDepth() {
        return rabbit.execute(channel -> channel.queueDeclarePassive(ShipmentMessagingConfig.WORK_QUEUE).getMessageCount());
    }

    private int workQueueConsumers() {
        return rabbit.execute(channel -> channel.queueDeclarePassive(ShipmentMessagingConfig.WORK_QUEUE).getConsumerCount());
    }

    @Test
    @EnabledIfSystemProperty(named = "shipment.rabbit.fault.live", matches = "true")
    void outboxRetriesAfterIsolatedBrokerRestart() throws Exception {
        String container = requireIsolatedBroker();
        createShipmentFixture();
        Long shipmentId = shipmentMapper.selectByOrderId(orderId).getId();
        jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?", LocalDateTime.now().plusSeconds(3), shipmentId);
        Long eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Long.class, shipmentId);
        boolean stopped = false;
        try {
            docker("stop", container);
            stopped = true;
            publisher.publishPending();
            assertEquals("PENDING", eventStatus(eventId));
            assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id=?", Integer.class, eventId));
        } finally {
            if (stopped) docker("start", container);
        }

        boolean brokerReady = false;
        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < readyDeadline && !brokerReady) {
            try {
                brokerReady = Boolean.TRUE.equals(rabbit.execute(channel -> channel.isOpen()));
            } catch (RuntimeException unavailable) {
                Thread.sleep(300);
            }
        }
        assertTrue(brokerReady);
        jdbc.update("UPDATE outbox_events SET next_attempt_at=NOW() WHERE id=?", eventId);
        publisher.publishPending();
        assertEquals("PUBLISHED", eventStatus(eventId));
        long confirmDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < confirmDeadline && shipmentMapper.selectById(shipmentId).getStatus() != ShipmentStatus.CONFIRMED) {
            Thread.sleep(200);
        }
        assertEquals(ShipmentStatus.CONFIRMED, shipmentMapper.selectById(shipmentId).getStatus());
        rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.WORK_ROUTING, eventId.toString());
        Thread.sleep(1000);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",
                Integer.class, eventId));
    }

    private String eventStatus(Long eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, eventId);
    }

    private String requireIsolatedBroker() {
        String container = System.getProperty("shipment.rabbit.test-container", "");
        if (!container.matches("codex-mq-acceptance-[a-z0-9]+")
                || !"5673".equals(System.getProperty("spring.rabbitmq.port"))) {
            throw new IllegalStateException("MQ 故障测试必须使用隔离容器和 5673 端口");
        }
        return container;
    }

    private void docker(String operation, String container) throws Exception {
        Process process = new ProcessBuilder("docker", operation, container).redirectErrorStream(true).start();
        if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("隔离 RabbitMQ 容器操作失败: " + operation);
        }
    }

    private void createShipmentFixture() {
        merchantId = user("merchant");
        buyerId = user("buyer");
        jdbc.update("INSERT INTO user_accounts (user_id,username,password_hash,role,status) " +
                "VALUES (?,?,?,'MERCHANT','ACTIVE')", merchantId, "rabbit_" + UUID.randomUUID(), "test-only");
        String shopName = "Rabbit物流-" + UUID.randomUUID();
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", merchantId, shopName);
        shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, shopName);
        jdbc.update("INSERT INTO orders (user_id,shop_id,total_amount,status,pay_expires_at,paid_at," +
                        "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) " +
                        "VALUES (?,?,?,'PAID',NOW(),NOW(),?,?,?,?,?,?,1)",
                buyerId, shopId, new BigDecimal("40.00"), new byte[]{1}, new byte[]{1},
                "北京市", "北京市", "海淀区", new byte[]{1});
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE shop_id=? AND user_id=?", Long.class, shopId, buyerId);

        shipments.ship(merchantId, shopId, orderId, new ShipmentCreateDTO("顺丰", "SF-RABBIT"));
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
        String name = "rabbit_" + kind + "_" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (nickname) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, name);
    }
}
