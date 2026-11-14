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
        "spring.autoconfigure.exclude=", "spring.session.data.redis.namespace=shiwushop:session:account-http"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountManagementHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;
    final ObjectMapper json = new ObjectMapper();
    TestAccounts fixtures;
    TestAccounts.Account admin;
    TestAccounts.Account user;

    @BeforeEach
    void prepare() {
        fixtures = new TestAccounts(jdbc);
        admin = fixtures.create("ADMIN", "Admin123!");
        user = fixtures.create("USER", "User123!");
    }

    @AfterEach
    void cleanup() {
        for (long id : new long[] {admin.id(), user.id()}) {
            for (String sessionId : sessions.findByPrincipalName(Long.toString(id)).keySet())
                sessions.deleteById(sessionId);
            redis.delete("shiwushop:auth:state:" + id);
        }
        jdbc.update("DELETE FROM account_action_items WHERE target_user_id=?", user.id());
        jdbc.update("DELETE FROM audit_events WHERE target_type='USER_ACCOUNT' AND target_id=?", user.id());
        fixtures.close();
    }

    @Test
    void deletionRequestRoutesStayClosedByDefault() throws Exception {
        try (var buyer = client()) {
            login(buyer, user);
            assertEquals(404, buyer.get("/users/deletion-request").statusCode());
        }
    }

    @Test
    void adminDisableIsIdempotentAuditedAndInvalidatesOldSession() throws Exception {
        try (var manager = client(); var buyer = client()) {
            login(manager, admin);
            login(buyer, user);
            String path = "/admin/users/" + user.id() + "/status";
            assertEquals(200, manager.request("PUT", path, Map.of("status", "DISABLED", "reason", "risk")).statusCode());
            assertEquals(401, buyer.get("/users/me").statusCode());
            assertEquals(200, manager.request("PUT", path, Map.of("status", "DISABLED", "reason", "repeat")).statusCode());
            assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE target_type='USER_ACCOUNT' AND target_id=?", user.id()));
            assertEquals(1L, count("SELECT COUNT(*) FROM account_action_items WHERE target_user_id=?", user.id()));
            assertEquals(200, manager.request("PUT", path, Map.of("status", "ACTIVE", "reason", "reviewed")).statusCode());
            assertEquals(2L, count("SELECT COUNT(*) FROM audit_events WHERE target_type='USER_ACCOUNT' AND target_id=?", user.id()));
            assertEquals(1L, count("SELECT COUNT(*) FROM account_action_items WHERE target_user_id=?", user.id()));
            assertEquals(401, buyer.get("/users/me").statusCode());
            assertEquals("RESOLVED", jdbc.queryForObject(
                    "SELECT status FROM account_action_items WHERE target_user_id=?", String.class, user.id()));
        }
    }

    @Test
    void userCannotChangeAccountStatus() throws Exception {
        try (var buyer = client()) {
            assertEquals(401, buyer.request("PUT", "/admin/users/" + user.id() + "/status",
                    Map.of("status", "DISABLED", "reason", "anonymous")).statusCode());
            login(buyer, user);
            assertEquals(403, buyer.request("PUT", "/admin/users/" + admin.id() + "/status",
                    Map.of("status", "DISABLED", "reason", "attack")).statusCode());
        }
    }

    @Test
    void indexedSessionsRevokeOtherAndPreserveCurrent() throws Exception {
        try (var current = client(); var other = client(); var stranger = client()) {
            login(current, user);
            login(other, user);
            login(stranger, admin);
            var listed = json.readTree(current.get("/users/sessions").body()).path("data");
            assertEquals(2, listed.size());
            assertFalse(listed.toString().contains(current.sessionId()));
            assertFalse(listed.toString().contains(other.sessionId()));
            String otherRef = null;
            for (var row : listed) {
                if (!row.path("current").asBoolean()) otherRef = row.path("sessionRef").asString();
            }
            assertNotNull(otherRef);
            assertEquals(401, current.post("/users/sessions/" + otherRef + "/revoke",
                    Map.of("currentPassword", "wrong")).statusCode());
            assertEquals(200, other.get("/users/me").statusCode());
            var strangerSessions = json.readTree(stranger.get("/users/sessions").body()).path("data");
            String strangerRef = strangerSessions.get(0).path("sessionRef").asString();
            assertEquals(404, current.post("/users/sessions/" + strangerRef + "/revoke",
                    Map.of("currentPassword", user.password())).statusCode());
            assertEquals(200, stranger.get("/users/me").statusCode());
            assertEquals(200, current.post("/users/sessions/" + otherRef + "/revoke",
                    Map.of("currentPassword", user.password())).statusCode());
            assertEquals(401, other.get("/users/me").statusCode());
            assertEquals(200, current.get("/users/me").statusCode());
            assertEquals(404, current.post("/users/sessions/" + otherRef + "/revoke",
                    Map.of("currentPassword", user.password())).statusCode());
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

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }
}
