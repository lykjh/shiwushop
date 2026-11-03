package com.shiwushop.integration.account;

import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserProfileHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String dbUser;
    @Value("${spring.datasource.password}") String dbPassword;
    TestAccounts accounts;
    TestAccounts.Account user;
    TestAccounts.Account other;
    HttpAccountClient client;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() throws Exception {
        accounts = new TestAccounts(jdbc);
        user = accounts.create("USER", "Shop123!");
        other = accounts.create("USER", "Shop123!");
        client = new HttpAccountClient("http://127.0.0.1:" + port);
        client.csrf();
        assertEquals(200, client.post("/users/login", Map.of(
                "username", user.username(), "password", user.password())).statusCode());
        client.csrf();
    }

    @AfterEach
    void cleanup() {
        client.close();
        jdbc.update("UPDATE users SET avatar_media_id=NULL WHERE id IN (?,?)", user.id(), other.id());
        jdbc.update("DELETE FROM media_assets WHERE uploaded_by_user_id IN (?,?)", user.id(), other.id());
        accounts.close();
    }

    long media(long owner, String status) {
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) VALUES (?,?, 'image/png',1,1,1,?,?)",
                owner, key, new byte[32], status);
        return jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key=?", Long.class, key);
    }

    Map<String, Object> body(String name, Long avatar) {
        var value = new HashMap<String, Object>();
        value.put("nickname", name);
        value.put("avatarMediaId", avatar);
        return value;
    }

    @Test
    void setsReplacesAndClearsAvatar() throws Exception {
        long first = media(user.id(), "READY");
        long second = media(user.id(), "READY");
        var response = client.request("PUT", "/users/profile", body(" 😀 ", first));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("😀", json.readTree(response.body()).path("data").path("nickname").asString());
        assertEquals(200, client.request("PUT", "/users/profile", body("b", second)).statusCode());
        assertEquals(200, client.request("PUT", "/users/profile", body("b", second)).statusCode());
        assertEquals(second, jdbc.queryForObject("SELECT avatar_media_id FROM users WHERE id=?", Long.class, user.id()));
        assertEquals(200, client.request("PUT", "/users/profile", body("b", null)).statusCode());
        assertNull(jdbc.queryForObject("SELECT avatar_media_id FROM users WHERE id=?", Long.class, user.id()));
    }

    @Test
    void rejectsUnavailableAvatarWithoutChangingProfile() throws Exception {
        long ready = media(user.id(), "READY");
        long otherMedia = media(other.id(), "READY");
        long staged = media(user.id(), "STAGED");
        assertEquals(200, client.request("PUT", "/users/profile", body("old", ready)).statusCode());
        assertEquals(404, client.request("PUT", "/users/profile", body("bad", otherMedia)).statusCode());
        assertEquals(404, client.request("PUT", "/users/profile", body("bad", Long.MAX_VALUE)).statusCode());
        assertEquals(409, client.request("PUT", "/users/profile", body("bad", staged)).statusCode());
        assertEquals("old", jdbc.queryForObject("SELECT nickname FROM users WHERE id=?", String.class, user.id()));
        assertEquals(ready, jdbc.queryForObject("SELECT avatar_media_id FROM users WHERE id=?", Long.class, user.id()));
    }

    @Test
    void acceptsFortySupplementaryCharactersAndRejectsFortyOne() throws Exception {
        assertEquals(200, client.request("PUT", "/users/profile", body("😀".repeat(40), null)).statusCode());
        assertEquals(400, client.request("PUT", "/users/profile", body("😀".repeat(41), null)).statusCode());
        assertEquals("😀".repeat(40), json.readTree(client.get("/users/profile").body()).path("data").path("nickname").asString());
    }

    @Test
    void requiresSessionAndCsrf() throws Exception {
        assertEquals(403, client.request("PUT", "/users/profile", body("x", null), false, Map.of()).statusCode());
        try (var anonymous = new HttpAccountClient("http://127.0.0.1:" + port)) {
            assertEquals(401, anonymous.get("/users/profile").statusCode());
        }
    }

    @Test
    void updatingProfileDoesNotChangeAccountFields() throws Exception {
        var before = jdbc.queryForMap("SELECT username,password_hash,role FROM user_accounts WHERE user_id=?", user.id());
        assertEquals(200, client.request("PUT", "/users/profile", body("b", null)).statusCode());
        assertEquals(before, jdbc.queryForMap("SELECT username,password_hash,role FROM user_accounts WHERE user_id=?", user.id()));
    }

    @Test
    void readingProfileUsesCurrentUser() throws Exception {
        assertEquals(200, client.request("PUT", "/users/profile", body("mine", null)).statusCode());
        try (var stranger = new HttpAccountClient("http://127.0.0.1:" + port)) {
            stranger.csrf();
            assertEquals(200, stranger.post("/users/login", Map.of("username", other.username(), "password", other.password())).statusCode());
            assertEquals(other.username(), json.readTree(stranger.get("/users/profile").body()).path("data").path("nickname").asString());
        }
    }

    @Test
    void concurrentUpdatesReturnWholeProfilesAndPersistOneWholeProfile() throws Exception {
        long first = media(user.id(), "READY");
        long second = media(user.id(), "READY");
        try (var otherClient = new HttpAccountClient("http://127.0.0.1:" + port);
             var lock = DriverManager.getConnection(url, dbUser, dbPassword);
             var observer = DriverManager.getConnection(url, dbUser, dbPassword);
             var pool = Executors.newFixedThreadPool(2)) {
            otherClient.csrf();
            assertEquals(200, otherClient.post("/users/login", Map.of(
                    "username", user.username(), "password", user.password())).statusCode());
            otherClient.csrf();
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                statement.setLong(1, user.id());
                statement.executeQuery().close();
            }
            long lockConnectionId;
            try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                lockConnectionId = rows.getLong(1);
            }
            try {
                var one = pool.submit(() -> client.request("PUT", "/users/profile", body("first", first)));
                var two = pool.submit(() -> otherClient.request("PUT", "/users/profile", body("second", second)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting = 0;
                String waitQuery = """
                        SELECT COUNT(DISTINCT waits.REQUESTING_THREAD_ID)
                        FROM performance_schema.data_lock_waits waits
                        JOIN performance_schema.data_locks requested
                          ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.threads blocker
                          ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
                        WHERE blocker.PROCESSLIST_ID = ? AND requested.OBJECT_SCHEMA = DATABASE()
                          AND requested.OBJECT_NAME = 'users' AND requested.INDEX_NAME = 'PRIMARY'
                          AND requested.LOCK_DATA = ?
                        """;
                try (var statement = observer.prepareStatement(waitQuery)) {
                    statement.setLong(1, lockConnectionId);
                    statement.setString(2, Long.toString(user.id()));
                    while (System.nanoTime() < deadline && waiting < 2) {
                        try (var rows = statement.executeQuery()) {
                            rows.next();
                            waiting = rows.getInt(1);
                        }
                        Thread.yield();
                    }
                }
                assertEquals(2, waiting, "both profile updates must wait on the held user row");
                lock.commit();
                var resultOne = one.get(15, TimeUnit.SECONDS);
                var resultTwo = two.get(15, TimeUnit.SECONDS);
                assertEquals(200, resultOne.statusCode(), resultOne.body());
                assertEquals(200, resultTwo.statusCode(), resultTwo.body());
                var a = json.readTree(resultOne.body()).path("data");
                var b = json.readTree(resultTwo.body()).path("data");
                assertEquals("first", a.path("nickname").asString());
                assertEquals(first, a.path("avatarMediaId").asLong());
                assertEquals("second", b.path("nickname").asString());
                assertEquals(second, b.path("avatarMediaId").asLong());
                var stored = jdbc.queryForMap("SELECT nickname,avatar_media_id FROM users WHERE id=?", user.id());
                assertTrue((stored.get("nickname").equals("first")
                        && ((Number) stored.get("avatar_media_id")).longValue() == first)
                        || (stored.get("nickname").equals("second")
                        && ((Number) stored.get("avatar_media_id")).longValue() == second));
            } finally {
                lock.rollback();
            }
        }
    }
}
