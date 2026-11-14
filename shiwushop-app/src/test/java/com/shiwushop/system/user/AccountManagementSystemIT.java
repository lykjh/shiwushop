package com.shiwushop.system.user;

import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 JAR 的账号状态与本人会话闭环，应用容器由外部启动。 */
class AccountManagementSystemIT {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adminStatusAndSelfSessionRevocationRejectOldRequests() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        try (var fixtures = new TestAccounts(jdbc)) {
            var admin = fixtures.create("ADMIN", "Admin123!");
            var user = fixtures.create("USER", "User123!");
            try (var manager = client(base); var current = client(base); var other = client(base)) {
                try {
                    login(manager, admin);
                    login(current, user);
                    login(other, user);
                    var listed = json.readTree(current.get("/users/sessions").body()).path("data");
                    assertEquals(2, listed.size());
                    String otherRef = null;
                    for (var row : listed)
                        if (!row.path("current").asBoolean()) otherRef = row.path("sessionRef").asString();
                    assertNotNull(otherRef);
                    assertEquals(200, current.post("/users/sessions/" + otherRef + "/revoke",
                            Map.of("currentPassword", user.password())).statusCode());
                    assertEquals(401, other.get("/users/me").statusCode());
                    assertEquals(200, current.get("/users/me").statusCode());
                    String path = "/admin/users/" + user.id() + "/status";
                    assertEquals(200, manager.request("PUT", path,
                            Map.of("status", "DISABLED", "reason", "system test")).statusCode());
                    assertEquals(401, current.get("/users/me").statusCode());
                    assertEquals(200, manager.request("PUT", path,
                            Map.of("status", "ACTIVE", "reason", "system test restore")).statusCode());
                    try (var fresh = client(base)) {
                        login(fresh, user);
                        assertEquals(200, fresh.get("/users/me").statusCode());
                    }
                } finally {
                    jdbc.update("DELETE FROM account_action_items WHERE target_user_id=?", user.id());
                    jdbc.update("DELETE FROM audit_events WHERE target_type='USER_ACCOUNT' AND target_id=?", user.id());
                }
            }
        }
    }

    private HttpAccountClient client(String base) throws Exception {
        var client = new HttpAccountClient(base);
        client.csrf();
        return client;
    }

    private void login(HttpAccountClient client, TestAccounts.Account account) throws Exception {
        assertEquals(200, client.post("/users/login",
                Map.of("username", account.username(), "password", account.password())).statusCode());
    }
}
