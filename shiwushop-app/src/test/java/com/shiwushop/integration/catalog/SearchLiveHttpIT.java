package com.shiwushop.integration.catalog;

import com.shiwushop.product.search.*;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式执行的 OpenSearch 真实链路；只允许使用 29200 与独立搜索测试 schema。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/shiwushop_search_http","spring.datasource.hikari.maximum-pool-size=2",
        "shop.search.url=http://127.0.0.1:29200","shop.search.discovery-enabled=true","shop.search.worker-enabled=false",
        "shop.search.cursor-secret=local-search-test-secret-at-least-32-bytes"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SearchLiveHttpIT {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc; @Autowired SearchGateway gateway;
    @Autowired SearchIndexSource source; @Autowired OutboxEventMapper events; @Autowired ObjectMapper json;
    CatalogFixture fixture; HttpAccountClient client; long first,second;
    @BeforeAll static void prepareIndices() throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            for(var index:Map.of("shiwushop-products-v2","shiwushop-products-read","shiwushop-shops-v1","shiwushop-shops-read","shiwushop-brands-v1","shiwushop-brands-read").entrySet()) {
                var body=Map.of("settings",Map.of("number_of_shards",1,"number_of_replicas",0),
                        "aliases",Map.of(index.getValue(),Map.of()),"mappings",Map.of("properties",Map.of(
                                "id",Map.of("type","long"),"name",Map.of("type","text","fields",Map.of("exact",Map.of("type","keyword"))))));
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:29200/"+index.getKey()))
                        .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
                assertTrue(response.statusCode()==200 || response.body().contains("resource_already_exists_exception"),response.body());
            }
        }
    }
    @BeforeEach void prepare() throws Exception {
        fixture=new CatalogFixture(jdbc);client=new HttpAccountClient("http://127.0.0.1:"+port);
        first=fixture.product("洁丽雅毛巾","ON_SALE","19.90",3);second=fixture.product("茶花收纳盒","ON_SALE","29.90",2);
        publish(first);publish(second);
    }
    void publish(long id) {
        events.insertSearchProductRefresh(id);
        new SearchOutboxWorker(events,source,gateway,json,Clock.systemDefaultZone(),true).processNext();
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id=? ORDER BY id DESC LIMIT 1",String.class,id));
    }
    @AfterEach void cleanup(){
        client.close();for(long id:List.of(first,second)){
            gateway.bulk(json.writeValueAsString(Map.of("delete",Map.of("_index","shiwushop-products-v2","_id",id)))+"\n");
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='PRODUCT' AND aggregate_id=?",id);
        }fixture.close();
    }
    @Test void realPitCursorTraversesIndexedCandidatesAndDbFiltersStaleDocuments() throws Exception {
        var response=client.get("/discovery/products?shopId="+fixture.shop+"&size=1");assertEquals(200,response.statusCode(),response.body());
        var data=json.readTree(response.body()).path("data");assertEquals(second,data.path("items").get(0).path("id").asLong());
        String cursor=data.path("nextCursor").asString();assertFalse(cursor.isBlank());
        var next=client.get("/discovery/products?shopId="+fixture.shop+"&size=1&cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200,next.statusCode(),next.body());assertEquals(first,json.readTree(next.body()).path("data").path("items").get(0).path("id").asLong());
        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?",second);
        var after=client.get("/discovery/products?shopId="+fixture.shop);assertEquals(200,after.statusCode(),after.body());
        assertEquals(1,json.readTree(after.body()).path("data").path("items").size());
    }
    @Test void changedQueryAndTamperedCursorAreRejected() throws Exception {
        var response=client.get("/discovery/products?shopId="+fixture.shop+"&size=1");
        String cursor=json.readTree(response.body()).path("data").path("nextCursor").asString();
        assertEquals(400,client.get("/discovery/products?shopId="+fixture.shop+"&size=2&cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8)).statusCode());
        assertEquals(400,client.get("/discovery/products?cursor=abc.def").statusCode());
    }
    @Test void outboxReplaysTheCurrentProductStateAndDeletesOffSaleDocument() throws Exception {
        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?",first);publish(first);
        var response=client.get("/discovery/products?shopId="+fixture.shop);assertEquals(200,response.statusCode(),response.body());
        assertEquals(1,json.readTree(response.body()).path("data").path("items").size());
    }
}
