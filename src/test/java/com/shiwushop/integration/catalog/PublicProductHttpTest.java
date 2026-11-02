package com.shiwushop.integration.catalog;

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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicProductHttpTest {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
    CatalogFixture fixture; HttpAccountClient client;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void prepare() { fixture = new CatalogFixture(jdbc); client = new HttpAccountClient("http://127.0.0.1:"+port); }
    @AfterEach void cleanup() { client.close(); fixture.close(); }
    @ParameterizedTest @ValueSource(strings = {"size=0", "size=101", "size=-1", "size=abc", "minPrice=-0.01", "maxPrice=-1",
            "minPrice=20&maxPrice=10", "afterId=0", "afterId=-1", "afterId=bad", "page=1", "includeTotal=false"})
    void rejectsInvalidListParameters(String query) throws Exception {
        var response=client.get("/products?shopId="+fixture.shop+"&"+query);
        assertEquals(400,response.statusCode(),response.body());
    }
    @Test void cursorTraversesOnlyVisibleMatchingProductsWithoutDuplicates() throws Exception {
        long first=fixture.product("洁丽雅纯棉毛巾", "ON_SALE", "19.90", 5);
        long second=fixture.product("茶花收纳盒", "ON_SALE", "29.90", 0);
        fixture.product("未发布收纳筐", "DRAFT", "9.90", 5);
        Set<Long> found=new LinkedHashSet<>(); String next="";
        do {
            var response=client.get("/products?shopId="+fixture.shop+"&size=1"+next);
            assertEquals(200,response.statusCode(),response.body()); var data=json.readTree(response.body()).path("data");
            for(var item:data.path("items")) assertTrue(found.add(item.path("id").asLong()),"重复商品");
            var cursor=data.path("nextCursor"); next=cursor.isNull() || cursor.isMissingNode()? "" : "&afterId="+cursor.asLong();
        } while(!next.isEmpty());
        assertEquals(Set.of(first,second),found);
    }
    @Test void inclusivePriceBoundsAndSoldOutFlagAreRealDatabaseValues() throws Exception {
        long id=fixture.product("洁丽雅纯棉毛巾", "ON_SALE", "19.90", 2);
        var response=client.get("/products?shopId="+fixture.shop+"&minPrice=19.90&maxPrice=19.90");
        assertEquals(200,response.statusCode(),response.body()); var items=json.readTree(response.body()).path("data").path("items");
        assertEquals(1,items.size()); assertEquals(id,items.get(0).path("id").asLong()); assertFalse(items.get(0).path("soldOut").asBoolean());
        jdbc.update("UPDATE skus SET stock=0 WHERE product_id=?",id);
        var soldOut=client.get("/products?shopId="+fixture.shop);
        assertTrue(json.readTree(soldOut.body()).path("data").path("items").get(0).path("soldOut").asBoolean());
    }
    @Test void detailHidesDraftOffSaleStoppedShopAndMissingProduct() throws Exception {
        long id=fixture.product("茶花收纳盒", "DRAFT", "29.90", 4);
        assertEquals(404,client.get("/products/"+id).statusCode());
        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?",id);
        assertEquals(404,client.get("/products/"+id).statusCode());
        jdbc.update("UPDATE products SET status='ON_SALE' WHERE id=?",id);
        assertEquals(200,client.get("/products/"+id).statusCode());
        jdbc.update("UPDATE shops SET enabled=0 WHERE id=?",fixture.shop);
        assertEquals(404,client.get("/products/"+id).statusCode());
        assertEquals(404,client.get("/products/9223372036854775807").statusCode());
    }
}
