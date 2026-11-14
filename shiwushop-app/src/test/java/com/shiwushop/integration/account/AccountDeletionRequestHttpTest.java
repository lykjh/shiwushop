package com.shiwushop.integration.account;

import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.account.deletion-request-enabled=true",
        "spring.session.data.redis.namespace=shiwushop:session:deletion-http"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountDeletionRequestHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    final ObjectMapper json = new ObjectMapper();
    TestAccounts fixtures;
    TestAccounts.Account user;
    TestAccounts.Account other;

    @BeforeEach
    void prepare() {
        fixtures = new TestAccounts(jdbc);
        user = fixtures.create("USER", "User123!");
        other = fixtures.create("USER", "Other123!");
    }

    @AfterEach
    void cleanup() {
        for (long id : new long[] {user.id(), other.id()}) {
            for (String sessionId : sessions.findByPrincipalName(Long.toString(id)).keySet())
                sessions.deleteById(sessionId);
            redis.delete("shiwushop:auth:state:" + id);
            jdbc.update("DELETE FROM account_deletion_requests WHERE user_id=?", id);
        }
        fixtures.close();
    }

    @Test
    void passwordProofOwnRecordsAndWithdrawAreIdempotent() throws Exception {
        try (var mine = client(); var stranger = client()) {
            assertEquals(401, mine.post("/users/deletion-request",
                    Map.of("currentPassword", user.password())).statusCode());
            login(mine, user);
            login(stranger, other);
            assertEquals(404, mine.get("/users/deletion-request").statusCode());
            assertEquals(401, mine.post("/users/deletion-request",
                    Map.of("currentPassword", "wrong")).statusCode());
            assertEquals(0L, count());

            var created = mine.post("/users/deletion-request", Map.of("currentPassword", user.password()));
            assertEquals(200, created.statusCode());
            var data = json.readTree(created.body()).path("data");
            assertEquals("PENDING", data.path("status").asString());
            assertFalse(data.has("userId"));
            assertFalse(data.has("id"));
            assertEquals(200, mine.post("/users/deletion-request",
                    Map.of("currentPassword", user.password())).statusCode());
            assertEquals(1L, count());
            assertEquals("PENDING", json.readTree(mine.get("/users/deletion-request").body())
                    .path("data").path("status").asString());
            assertEquals(404, stranger.get("/users/deletion-request").statusCode());

            assertEquals(401, mine.post("/users/deletion-request/withdraw",
                    Map.of("currentPassword", "wrong")).statusCode());
            assertEquals("PENDING", json.readTree(mine.get("/users/deletion-request").body())
                    .path("data").path("status").asString());
            assertEquals(200, mine.post("/users/deletion-request/withdraw",
                    Map.of("currentPassword", user.password())).statusCode());
            assertEquals(200, mine.post("/users/deletion-request/withdraw",
                    Map.of("currentPassword", user.password())).statusCode());
            assertEquals("WITHDRAWN", json.readTree(mine.get("/users/deletion-request").body())
                    .path("data").path("status").asString());
            assertEquals(1L, count());
        }
    }

    private HttpAccountClient client() throws Exception {
        var client = new HttpAccountClient("http://127.0.0.1:" + port);
        client.csrf();
        return client;
    }

    private void login(HttpAccountClient client, TestAccounts.Account account) throws Exception {
        assertEquals(200, client.post("/users/login",
                Map.of("username", account.username(), "password", account.password())).statusCode());
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_requests WHERE user_id=?", Long.class, user.id());
    }
}
