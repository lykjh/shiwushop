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
}
