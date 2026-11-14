package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=4",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    CommerceFixture fixture;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() throws Exception {
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void cleanup() {
        if (fixture != null)
            fixture.close();
    }

    @Test
    void cartCheckoutWritesSnapshotsDeletesCartAndReusesIdempotencyResult() throws Exception {
        var added = fixture.client.post("/cart/items", Map.of("skuId", fixture.sku, "quantity", 2));
        assertEquals(200, added.statusCode(), added.body());
        long item = json.readTree(added.body()).path("data").path("id").asLong();
        var body = Map.<String, Object>of("cartItemIds", List.of(item), "addressId", fixture.address, "confirmedTotal",
                "39.80", "idempotencyKey", "cart-checkout");
        var first = fixture.client.post("/checkouts", body);
        assertEquals(200, first.statusCode(), first.body());
        var retry = fixture.client.post("/checkouts", body);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(json.readTree(first.body()), json.readTree(retry.body()));
        long order = json.readTree(first.body()).path("data").path("orders").get(0).path("id").asLong();
        var detail = fixture.client.get("/orders/" + order);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(json.readTree(detail.body()).path("data").path("rewardSummary").isNull());
        assertEquals(18, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id=?", Integer.class,
                fixture.buyer.id()));
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, fixture.buyer.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "19.89", "19.91"})
    void priceMismatchDoesNotDeductStockOrKeepCheckoutRequest(String price) throws Exception {
        var response = fixture.client.post("/checkouts/direct", fixture.direct("wrong-price", 1, price));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("PRICE_CHANGED", json.readTree(response.body()).path("data").path("reason").asString());
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?", Integer.class,
                fixture.buyer.id()));
    }

    @Test
    void insufficientStockRollsBackTheEntireRequest() throws Exception {
        var response = fixture.client.post("/checkouts/direct", fixture.direct("too-many", 21, "417.90"));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, fixture.buyer.id()));
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
    }

    @Test
    void changedPayloadUnderSameIdempotencyKeyIsRejected() throws Exception {
        fixture.order("same-key");
        var response = fixture.client.post("/checkouts/direct", fixture.direct("same-key", 2, "39.80"));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(19, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
    }

    @Test
    void concurrentRequestsForLastItemHaveOneWinner() throws Exception {
        jdbc.update("UPDATE skus SET stock=1 WHERE id=?", fixture.sku);
        try (var second = CommerceFixture.login("http://127.0.0.1:" + port,
                fixture.buyer); var pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            var a = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return fixture.client.post("/checkouts/direct", fixture.direct("last-a", 1, "19.90")).statusCode();
            });
            var b = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.post("/checkouts/direct", fixture.direct("last-b", 1, "19.90")).statusCode();
            });
            var result = new ArrayList<>(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            Collections.sort(result);
            assertEquals(List.of(200, 409), result);
            assertEquals(0, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        }
    }

    @Test
    void closingFixtureRemovesItsMemberLedgerBeforeOrdersAndUsers() throws Exception {
        long order = fixture.order("member-cleanup");
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        long buyer = fixture.buyer.id();
        long merchant = fixture.catalog.merchant.id();
        for (long user : List.of(buyer, merchant))
            jdbc.update("INSERT INTO member_accounts(user_id,level_code,joined_at,reward_start_at) "
                    + "VALUES (?,'ORDINARY',NOW(),NOW()) ON DUPLICATE KEY UPDATE user_id=member_accounts.user_id", user);
        String key = "fixture-cleanup-" + UUID.randomUUID();
        long growth = fixture.catalog.insert("INSERT INTO member_growth_entries "
                + "(user_id,order_item_id,event_kind,operation_key,delta_amount,balance_after,old_level_code,new_level_code) "
                + "VALUES (?,?,'EARN',?,1,1,'ORDINARY','ORDINARY')", buyer, item, key + "-growth");
        jdbc.update("INSERT INTO member_level_changes "
                + "(user_id,to_level_code,effective_at,operation_key,growth_entry_id) "
                + "VALUES (?,'ORDINARY',NOW(),?,?)", buyer, key + "-level", growth);
        long lot = fixture.catalog.insert("INSERT INTO member_point_lots "
                + "(user_id,source_key,order_item_id,issued_units,available_units,earned_at,expires_at,status) "
                + "VALUES (?,?,?,100,100,NOW(),DATE_ADD(NOW(),INTERVAL 1 YEAR),'ACTIVE')", buyer, key + "-lot", item);
        long entry = fixture.catalog.insert("INSERT INTO member_point_entries "
                + "(user_id,event_kind,operation_key,order_id,available_delta,available_after,held_after,debt_after) "
                + "VALUES (?,'EARN',?,?,100,100,0,0)", buyer, key + "-entry", order);
        jdbc.update("INSERT INTO member_point_allocations(entry_id,lot_id,allocation_kind,units) "
                + "VALUES (?,?,'EARN',100)", entry, lot);

        CommerceFixture closing = fixture;
        fixture = null;
        closing.close();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (?,?)", Integer.class,
                buyer, merchant));
    }
}
