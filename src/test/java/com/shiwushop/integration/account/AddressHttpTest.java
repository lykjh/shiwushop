package com.shiwushop.integration.account;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AddressHttpTest {
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
    TestAccounts accounts; TestAccounts.Account buyer; HttpAccountClient client;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void prepare() throws Exception {
        accounts=new TestAccounts(jdbc); buyer=accounts.create("USER","Shop123!");
        client=new HttpAccountClient("http://127.0.0.1:"+port); client.csrf();
        assertEquals(200,client.post("/users/login",Map.of("username",buyer.username(),"password",buyer.password())).statusCode()); client.csrf();
    }
    @AfterEach void cleanup() { client.close(); jdbc.update("DELETE FROM addresses WHERE user_id=?",buyer.id()); accounts.close(); }
    static Map<String,Object> address() {
        return new HashMap<>(Map.of("consignee","张明","phone","13812345678","province","江苏省","city","南京市",
                "district","鼓楼区","detail","中山北路28号","isDefault",0));
    }
    static Stream<Map.Entry<String,Object>> invalidFields() {
        List<Map.Entry<String,Object>> result=new ArrayList<>();
        for(String field:List.of("consignee","phone","province","city","district","detail")) {
            result.add(new AbstractMap.SimpleEntry<>(field,null)); result.add(Map.entry(field," "));
        }
        result.add(Map.entry("consignee","张".repeat(21))); result.add(Map.entry("province","省".repeat(65)));
        result.add(Map.entry("city","市".repeat(65))); result.add(Map.entry("district","区".repeat(65)));
        result.add(Map.entry("detail","路".repeat(256))); result.add(Map.entry("phone","1234567890"));
        result.add(Map.entry("phone","123456789012")); result.add(Map.entry("phone","1381234abcd"));
        result.add(Map.entry("isDefault",-1)); result.add(Map.entry("isDefault",2)); return result.stream();
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsInvalidFieldWithoutStoringAddress(Map.Entry<String,Object> value) throws Exception {
        var body=address(); body.put(value.getKey(),value.getValue());
        var response=client.post("/addresses",body); assertEquals(400,response.statusCode(),response.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE user_id=?",Integer.class,buyer.id()));
    }
    @Test void storesCiphertextReturnsPlaintextOnlyToOwnerAndMaintainsDefault() throws Exception {
        var first=client.post("/addresses",address()); assertEquals(200,first.statusCode(),first.body());
        long id=json.readTree(first.body()).path("data").path("id").asLong();
        assertEquals(1,json.readTree(first.body()).path("data").path("isDefault").asInt());
        byte[] cipher=jdbc.queryForObject("SELECT detail_ciphertext FROM addresses WHERE id=?",byte[].class,id);
        assertNotNull(cipher); assertFalse(new String(cipher,StandardCharsets.UTF_8).contains("中山北路28号"));
        var second=client.post("/addresses",address()); assertEquals(200,second.statusCode());
        long secondId=json.readTree(second.body()).path("data").path("id").asLong();
        assertEquals(200,client.request("DELETE","/addresses/"+id,Map.of()).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT is_default FROM addresses WHERE id=?",Integer.class,secondId));
        var listed=client.get("/addresses"); assertEquals(200,listed.statusCode());
        assertEquals(1,json.readTree(listed.body()).path("data").size());
        assertEquals("中山北路28号",json.readTree(listed.body()).path("data").get(0).path("detail").asString());
        try(var anonymous=new HttpAccountClient("http://127.0.0.1:"+port)) { assertEquals(401,anonymous.get("/addresses").statusCode()); }
    }
    @Test void anotherUserCannotUpdateOrDeleteTheAddress() throws Exception {
        long id=json.readTree(client.post("/addresses",address()).body()).path("data").path("id").asLong();
        var other=accounts.create("USER","Shop456!");
        try(var stranger=new HttpAccountClient("http://127.0.0.1:"+port)) {
            stranger.csrf(); assertEquals(200,stranger.post("/users/login",Map.of("username",other.username(),"password",other.password())).statusCode()); stranger.csrf();
            assertEquals(404,stranger.request("PUT","/addresses/"+id,address()).statusCode());
            assertEquals(404,stranger.request("DELETE","/addresses/"+id,Map.of()).statusCode());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE id=? AND deleted_at IS NULL",Integer.class,id));
    }
}
