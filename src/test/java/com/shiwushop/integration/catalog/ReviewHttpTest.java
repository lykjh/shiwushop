package com.shiwushop.integration.catalog;

import com.shiwushop.support.*;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=2","shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReviewHttpTest {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;@MockitoBean SdkAlipayGateway gateway;
    CommerceFixture f;long order,item;String path;final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception {
        PaymentHttpFixture.configure(gateway);f=new CommerceFixture(jdbc,"http://127.0.0.1:"+port);order=PaymentHttpFixture.paidOrder(f,jdbc);
        item=jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?",Long.class,order);
        assertEquals(200,f.merchantClient.post("/merchant/shops/"+f.catalog.shop+"/orders/"+order+"/shipment",Map.of("carrierName","顺丰速运","trackingNo","SF"+order)).statusCode());
        assertEquals(200,f.client.post("/orders/"+order+"/shipment/confirm",Map.of()).statusCode());path="/orders/"+order+"/items/"+item+"/review";
    }
    @AfterEach void cleanup(){if(f!=null)f.close();}
    static Stream<Map<String,Object>> invalidReviews(){return Stream.of(Map.of("rating",0,"content","使用体验很好"),Map.of("rating",6,"content","使用体验很好"),
            Map.of("rating",5,"content"," "),Map.of("rating",5,"content","好".repeat(2001)),Map.of("rating",5,"content","好","imageMediaIds",List.of(1,1)),
            Map.of("rating",5,"content","好","imageMediaIds",List.of(-1)));}
    @ParameterizedTest @MethodSource("invalidReviews")
    void invalidReviewDoesNotChangeSummary(Map<String,Object> body) throws Exception {
        var response=f.client.post(path,body);assertEquals(400,response.statusCode(),response.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE order_item_id=?",Integer.class,item));
    }
    @Test void createFollowUpAndDeleteKeepPublicSummaryConsistent() throws Exception {
        var response=f.client.post(path,Map.of("rating",4,"content","毛巾吸水性很好"));assertEquals(200,response.statusCode(),response.body());
        long review=json.readTree(response.body()).path("data").path("id").asLong();
        assertEquals(409,f.client.post(path,Map.of("rating",5,"content","重复评价")).statusCode());
        var summary=f.client.get("/products/"+f.product+"/review-summary");assertEquals(200,summary.statusCode(),summary.body());
        assertEquals(1,json.readTree(summary.body()).path("data").path("reviewCount").asLong());assertEquals(4,json.readTree(summary.body()).path("data").path("averageRating").asDouble());
        assertEquals(200,f.client.post("/reviews/"+review+"/follow-up",Map.of("content","洗后没有掉色")).statusCode());
        assertEquals(409,f.client.post("/reviews/"+review+"/follow-up",Map.of("content","重复追评")).statusCode());
        for(int n=0;n<2;n++)assertEquals(200,f.client.request("DELETE","/reviews/"+review,Map.of()).statusCode());
        var after=f.client.get("/products/"+f.product+"/review-summary");assertEquals(0,json.readTree(after.body()).path("data").path("reviewCount").asLong());
    }
}
