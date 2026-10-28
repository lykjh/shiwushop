package com.shiwushop.search;

import com.shiwushop.catalog.search.SearchGateway;
import com.shiwushop.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchGatewayTests {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void opensProductReadAliasPitAndMapsExpiredSearchTo410() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shiwushop-products-read/_search/point_in_time", exchange -> {
            byte[] body = "{\"pit_id\":\"test-pit\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/_search", exchange -> {
            byte[] body = "{\"error\":\"pit missing\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        SearchGateway gateway = new SearchGateway(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort());
        assertEquals("test-pit", gateway.openPit());
        assertEquals(410, assertThrows(BusinessException.class,
                () -> gateway.search(Map.of("query", Map.of("match_all", Map.of())))).getCode());
    }

    @Test
    void sendsDeleteAndIgnoresUnsuccessfulCleanup() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_search/point_in_time", exchange -> {
            method.set(exchange.getRequestMethod());
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"pits\":[{\"pit_id\":\"renewed-pit\",\"successful\":false}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        SearchGateway gateway = new SearchGateway(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort());

        assertDoesNotThrow(() -> gateway.closePit("renewed-pit"));
        assertEquals("DELETE", method.get());
        assertEquals("renewed-pit", new ObjectMapper().readTree(payload.get()).path("pit_id").get(0).asText());
    }
}
