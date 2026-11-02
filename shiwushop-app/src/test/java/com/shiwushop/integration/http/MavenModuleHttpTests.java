package com.shiwushop.integration.http;

import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP 检查 app 对 common/core/search 的装配，不使用 Controller 或业务替身。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.session.data.redis.namespace=shiwushop:session:maven-module-http",
        "shop.search.discovery-enabled=false", "shop.search.worker-enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MavenModuleHttpTests {
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test void categoriesAreLoadedThroughCoreMapperResources() throws Exception {
        try (var client = new HttpAccountClient("http://127.0.0.1:"+port)) {
            var response = client.get("/categories"); assertEquals(200,response.statusCode(),response.body());
            var body=json.readTree(response.body()); assertEquals(200,body.path("code").asInt()); assertTrue(body.path("data").isArray());
        }
    }

    @Test void productsRetainPublicCursorResponse() throws Exception {
        try (var client = new HttpAccountClient("http://127.0.0.1:"+port)) {
            var response=client.get("/products?size=1"); assertEquals(200,response.statusCode(),response.body());
            var page=json.readTree(response.body()).path("data");
            assertTrue(page.path("items").isArray()); assertEquals(1,page.path("size").asInt()); assertTrue(page.has("hasMore"));
        }
    }

    @Test void appSecurityAndRedisSessionStillIssueCsrf() throws Exception {
        try (var client = new HttpAccountClient("http://127.0.0.1:"+port)) {
            client.csrf(); assertFalse(client.sessionId().isBlank());
            assertEquals(401,client.get("/users/me").statusCode());
        }
    }

    @Test void searchLibraryIsWiredAndKeepsItsDisabledResponse() throws Exception {
        try (var client = new HttpAccountClient("http://127.0.0.1:"+port)) {
            var response=client.get("/discovery/products?q=phone");
            assertEquals(503,response.statusCode(),response.body());
            assertEquals("商品发现索引尚未就绪",json.readTree(response.body()).path("message").asString());
        }
    }
}
