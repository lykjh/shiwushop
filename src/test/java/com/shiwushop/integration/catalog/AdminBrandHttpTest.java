package com.shiwushop.integration.catalog;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=","spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AdminBrandHttpTest {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;TestAccounts accounts;HttpAccountClient admin;
    final List<Long> ids=new ArrayList<>();final ObjectMapper json=new ObjectMapper();String name;
    @BeforeEach void prepare() throws Exception{accounts=new TestAccounts(jdbc);var account=accounts.create("ADMIN","Admin123!");name="洁丽雅"+account.id();admin=CommerceFixture.login("http://127.0.0.1:"+port,account);}
    @AfterEach void cleanup(){admin.close();for(long id:ids){jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='BRAND' AND aggregate_id=?",id);jdbc.update("DELETE FROM brands WHERE id=?",id);}accounts.close();}
    long create() throws Exception {var response=admin.post("/admin/brands",Map.of("name",name,"enabled",1));assertEquals(200,response.statusCode(),response.body());long id=json.readTree(response.body()).path("data").path("id").asLong();ids.add(id);return id;}
    @Test void duplicateNameDoesNotCreateSecondBrand() throws Exception{create();var response=admin.post("/admin/brands",Map.of("name",name,"enabled",1));assertEquals(409,response.statusCode(),response.body());}
    @Test void disabledBrandIsRemovedFromPublicList() throws Exception{
        long id=create();var response=admin.request("PUT","/admin/brands/"+id,Map.of("name",name,"enabled",0));assertEquals(200,response.statusCode(),response.body());
        var listed=admin.get("/brands");assertEquals(200,listed.statusCode());for(var brand:json.readTree(listed.body()).path("data"))assertNotEquals(id,brand.path("id").asLong());
    }
    @Test void oversizedNameIsRejected() throws Exception {var response=admin.post("/admin/brands",Map.of("name","牌".repeat(51),"enabled",1));assertEquals(400,response.statusCode(),response.body());}
}
