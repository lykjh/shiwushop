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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.autoconfigure.exclude=","spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AdminCategoryHttpTest {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;
    TestAccounts accounts;HttpAccountClient admin;final List<Long> ids=new ArrayList<>();final ObjectMapper json=new ObjectMapper();long root;
    @BeforeEach void prepare() throws Exception {
        accounts=new TestAccounts(jdbc);var account=accounts.create("ADMIN","Admin123!");admin=CommerceFixture.login("http://127.0.0.1:"+port,account);
        root=create("家居日用"+account.id(),null);
    }
    long create(String name,Long parent) throws Exception {
        Map<String,Object> body=new HashMap<>();body.put("name",name);if(parent!=null)body.put("parentId",parent);
        var response=admin.post("/admin/categories",body);assertEquals(200,response.statusCode(),response.body());
        long id=json.readTree(response.body()).path("data").path("id").asLong();ids.add(id);return id;
    }
    @AfterEach void cleanup(){admin.close();Collections.reverse(ids);for(long id:ids){jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='CATEGORY' AND aggregate_id=?",id);jdbc.update("DELETE FROM categories WHERE id=?",id);}accounts.close();}
    @ParameterizedTest @ValueSource(strings={""," ","abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"})
    void invalidNameReturns400WithoutCreatingChild(String name) throws Exception {
        var response=admin.post("/admin/categories",Map.of("name",name,"parentId",root));assertEquals(400,response.statusCode(),response.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE parent_id=?",Integer.class,root));
    }
    @Test void oneAndFiftyUnicodeCharactersAreAccepted() throws Exception {create("家",root);create("家".repeat(50),root);}
    @Test void sameParentNameIsUniqueButDifferentParentsMayReuseName() throws Exception {
        long first=create("厨房用品",root);var duplicate=admin.post("/admin/categories",Map.of("name","厨房用品","parentId",root));
        assertEquals(409,duplicate.statusCode(),duplicate.body());create("厨房用品",first);
    }
    @Test void hierarchyCannotExceedThreeLevelsOrContainSelfParent() throws Exception {
        long second=create("厨房用品",root),third=create("厨房收纳",second);
        assertEquals(400,admin.post("/admin/categories",Map.of("name","第四级","parentId",third)).statusCode());
        var self=admin.request("PUT","/admin/categories/"+second,Map.of("name","厨房用品","parentId",second,"visible",1,"sortOrder",0));
        assertEquals(409,self.statusCode(),self.body());
        assertEquals(409,admin.request("DELETE","/admin/categories/"+second,Map.of()).statusCode());
    }
    @Test void ordinaryUserCannotAccessAdminTree() throws Exception {
        var user=accounts.create("USER","Shop123!");try(var client=CommerceFixture.login("http://127.0.0.1:"+port,user)){
            assertEquals(403,client.get("/admin/categories").statusCode());assertEquals(403,client.post("/admin/categories",Map.of("name","越权分类")).statusCode());}
    }
}
