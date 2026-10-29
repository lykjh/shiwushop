package com.shiwushop.shipment;

import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.commerce.vo.shipment.ShipmentVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.commerce.entity.shipment.Shipment;
import com.shiwushop.commerce.entity.status.ShipmentStatus;
import com.shiwushop.commerce.mapper.shipment.ShipmentMapper;
import com.shiwushop.commerce.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ShipmentConcurrencyTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShipmentService service;
    @Autowired private ShipmentMapper shipments;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> shopIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long orderId : orderIds) {
            jdbc.update("DELETE FROM audit_events WHERE target_type='SHIPMENT' AND target_id IN " +
                    "(SELECT id FROM shipments WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id IN " +
                    "(SELECT id FROM shipments WHERE order_id=?)", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
        }
        for (Long shopId : shopIds) jdbc.update("DELETE FROM shops WHERE id=?", shopId);
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    @Test
    void concurrentShipmentsCreateOneRecordAndOneEvent() throws Exception {
        Fixture fixture = fixture();
        ShipmentCreateDTO request = new ShipmentCreateDTO("顺丰", "SF123");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<ShipmentVO> first = pool.submit(() -> {
                start.await();
                return service.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(), request);
            });
            Future<ShipmentVO> second = pool.submit(() -> {
                start.await();
                return service.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(), request);
            });
            start.countDown();
            assertEquals("SHIPPED", first.get().status());
            assertEquals("SHIPPED", second.get().status());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",
                Integer.class, fixture.orderId()));
        Shipment shipment = shipments.selectByOrderId(fixture.orderId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",
                Integer.class, shipment.getId()));
    }

    @Test
    void buyerAndSystemConfirmationCannotOverwriteEachOther() throws Exception {
        Fixture fixture = fixture();
        service.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                new ShipmentCreateDTO("顺丰", "SF123"));
        Shipment shipment = shipments.selectByOrderId(fixture.orderId());
        jdbc.update("UPDATE shipments SET shipped_at=?, auto_confirm_at=? WHERE id=?",
                LocalDateTime.now().minusDays(11), LocalDateTime.now().minusSeconds(1), shipment.getId());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> buyer = pool.submit(() -> {
                start.await();
                return service.confirmByBuyer(fixture.buyer(), fixture.orderId());
            });
            Future<?> system = pool.submit(() -> {
                start.await();
                service.confirmDue(shipment.getId());
                return null;
            });
            start.countDown();
            buyer.get();
            system.get();
        }
        Shipment confirmed = shipments.selectByOrderId(fixture.orderId());
        assertEquals(ShipmentStatus.CONFIRMED, confirmed.getStatus());
        assertNotNull(confirmed.getConfirmedAt());
        assertTrue("BUYER".equals(confirmed.getConfirmSource().name())
                || "SYSTEM".equals(confirmed.getConfirmSource().name()));
        assertEquals(confirmed.getConfirmSource().name(),
                service.confirmByBuyer(fixture.buyer(), fixture.orderId()).confirmSource());
    }

    @Test
    void concurrentDifferentTrackingNumbersRejectTheSecondWrite() throws Exception {
        Fixture fixture = fixture();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> shipResult(start, fixture, "SF123"));
            Future<String> second = pool.submit(() -> shipResult(start, fixture, "OTHER"));
            start.countDown();
            List<String> results = List.of(first.get(), second.get());
            assertTrue(results.contains("SHIPPED"));
            assertTrue(results.contains("409"));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?",
                Integer.class, fixture.orderId()));
    }

    private String shipResult(CountDownLatch start, Fixture fixture, String trackingNo) throws InterruptedException {
        start.await();
        try {
            return service.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                    new ShipmentCreateDTO("顺丰", trackingNo)).status();
        } catch (BusinessException conflict) {
            return Integer.toString(conflict.getCode());
        }
    }

    private Fixture fixture() {
        Long merchant = user("MERCHANT");
        Long buyer = user("USER");
        String shopName = "并发物流-" + UUID.randomUUID();
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", merchant, shopName);
        Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, shopName);
        shopIds.add(shopId);
        jdbc.update("INSERT INTO orders (user_id,shop_id,total_amount,status,pay_expires_at,paid_at," +
                        "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) " +
                        "VALUES (?,?,?,'PAID',NOW(),NOW(),?,?,?,?,?,?,1)",
                buyer, shopId, new BigDecimal("40.00"), new byte[]{1}, new byte[]{1},
                "北京市", "北京市", "海淀区", new byte[]{1});
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE shop_id=? AND user_id=?",
                Long.class, shopId, buyer);
        orderIds.add(orderId);
        return new Fixture(merchant, buyer, shopId, orderId);
    }

    private Long user(String role) {
        String name = "shipment_concurrent_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO users (nickname) VALUES (?)", name);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, name);
        userIds.add(userId);
        jdbc.update("INSERT INTO user_accounts (user_id,username,password_hash,role,status) " +
                "VALUES (?,?,? ,?,'ACTIVE')", userId, name, "test-only", role);
        return userId;
    }

    private record Fixture(Long merchant, Long buyer, Long shopId, Long orderId) { }
}
