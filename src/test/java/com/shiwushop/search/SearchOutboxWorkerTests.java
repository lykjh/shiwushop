package com.shiwushop.search;

import com.shiwushop.catalog.search.SearchGateway;
import com.shiwushop.catalog.search.SearchIndexSource;
import com.shiwushop.catalog.search.SearchOutboxWorker;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "shop.search.worker-enabled=false")
class SearchOutboxWorkerTests {
    private static final long EVENT_ID = -9000000002L;
    private static final long PRODUCT_ID = -9001L;
    @Autowired JdbcTemplate db;
    @Autowired OutboxEventMapper outbox;
    @Autowired SearchIndexSource source;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private int responseCode;
    private String responseBody;

    @BeforeEach
    void setUp() throws Exception {
        db.update("DELETE FROM outbox_events WHERE id = ?", EVENT_ID);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_bulk", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void cleanUp() {
        if (server != null) server.stop(0);
        db.update("DELETE FROM outbox_events WHERE id = ?", EVENT_ID);
        db.update("DELETE FROM skus WHERE id = ?", PRODUCT_ID);
        db.update("DELETE FROM products WHERE id = ?", PRODUCT_ID);
        db.update("DELETE FROM categories WHERE id = ?", PRODUCT_ID);
        db.update("DELETE FROM shops WHERE id = ?", PRODUCT_ID);
        db.update("DELETE FROM users WHERE id = ?", PRODUCT_ID);
    }

    @Test
    void productEventIndexesCurrentPublicDocumentThenPublishes() {
        db.update("INSERT INTO users(id) VALUES (?)", PRODUCT_ID);
        db.update("INSERT INTO categories(id, level, name, visible) VALUES (?, 1, 'S01 category', 1)", PRODUCT_ID);
        db.update("INSERT INTO shops(id, owner_user_id, name, enabled) VALUES (?, ?, 'S01 shop', 1)",
                PRODUCT_ID, PRODUCT_ID);
        db.update("INSERT INTO products(id, shop_id, category_id, name, status) " +
                "VALUES (?, ?, ?, 'S01 product', 'ON_SALE')", PRODUCT_ID, PRODUCT_ID, PRODUCT_ID);
        db.update("INSERT INTO skus(id, product_id, option_signature, price, stock) " +
                "VALUES (?, ?, REPEAT('a', 64), 12.50, 3)", PRODUCT_ID, PRODUCT_ID);
        db.update("INSERT INTO outbox_events(id, event_type, aggregate_type, aggregate_id, payload, " +
                "status, next_attempt_at) VALUES (?, 'SEARCH_PRODUCT_REFRESH', 'PRODUCT', ?, '{}', 'PENDING', NOW())",
                EVENT_ID, PRODUCT_ID);
        responseCode = 200;
        responseBody = "{\"items\":[{\"index\":{\"status\":201}}]}";

        var document = source.productDocuments(List.of(PRODUCT_ID));
        assertTrue(document.containsKey(PRODUCT_ID), document.toString());

        worker().processNext();

        assertEquals("PUBLISHED", db.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, EVENT_ID));
        assertTrue(body.get().contains("\"_index\":\"shiwushop-products-v2\""));
        assertTrue(body.get().contains("\"categoryPathIds\":[-9001]"));
        assertTrue(body.get().contains("\"availablePrices\":[12.50]"));
        assertTrue(body.get().contains("\"reviewCount\":0"));
    }

    @Test
    void failedBulkKeepsEventPendingForRetry() {
        db.update("INSERT INTO outbox_events(id, event_type, aggregate_type, aggregate_id, payload, " +
                "status, next_attempt_at) VALUES (?, 'SEARCH_SHOP_REFRESH', 'SHOP', ?, '{}', 'PENDING', NOW())",
                EVENT_ID, PRODUCT_ID);
        responseCode = 503;
        responseBody = "{\"error\":\"offline\"}";

        worker().processNext();

        assertEquals("PENDING", db.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, EVENT_ID));
        assertEquals(1, db.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, EVENT_ID));
        assertTrue(body.get().contains("\"delete\""));
    }

    @Test
    void shopEventIndexesLookupDocument() {
        db.update("INSERT INTO users(id) VALUES (?)", PRODUCT_ID);
        db.update("INSERT INTO shops(id, owner_user_id, name, enabled) VALUES (?, ?, 'S01 shop', 1)",
                PRODUCT_ID, PRODUCT_ID);
        db.update("INSERT INTO outbox_events(id, event_type, aggregate_type, aggregate_id, payload, " +
                "status, next_attempt_at) VALUES (?, 'SEARCH_SHOP_REFRESH', 'SHOP', ?, '{}', 'PENDING', NOW())",
                EVENT_ID, PRODUCT_ID);
        responseCode = 200;
        responseBody = "{\"items\":[{\"index\":{\"status\":201}}]}";

        worker().processNext();

        assertEquals("PUBLISHED", db.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, EVENT_ID));
        assertTrue(body.get().contains("\"enabled\":true"));
    }

    private SearchOutboxWorker worker() {
        SearchGateway gateway = new SearchGateway(json, "http://127.0.0.1:" + server.getAddress().getPort());
        return new SearchOutboxWorker(outbox, source, gateway, json, clock, true);
    }
}
