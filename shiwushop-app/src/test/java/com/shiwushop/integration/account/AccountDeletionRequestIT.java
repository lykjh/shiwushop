package com.shiwushop.integration.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.user.service.AccountDeletionRequestService;
import com.shiwushop.user.service.AccountTradeGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class AccountDeletionRequestIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountDeletionRequestService requests;
    @Autowired AccountTradeGate tradeGate;
    @Autowired PlatformTransactionManager transactions;
    TestAccounts fixtures;
    TestAccounts.Account user;

    @BeforeEach
    void prepare() {
        fixtures = new TestAccounts(jdbc);
        user = fixtures.create("USER", "User123!");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM account_deletion_requests WHERE user_id=?", user.id());
        fixtures.close();
    }

    @Test
    void pendingBlocksNewTradeAndWithdrawReopensGate() {
        int version = jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, user.id());
        var first = requests.request(user.id(), version);
        assertNotNull(first.id());
        assertEquals("PENDING", first.status());
        assertEquals(first.requestedAt().plusDays(7), first.deadlineAt());
        assertEquals(first.id(), requests.request(user.id(), version).id());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_deletion_requests WHERE user_id=?", Long.class, user.id()));
        var tx = new TransactionTemplate(transactions);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> tx.execute(ignored -> { tradeGate.requireCanStartTrade(user.id()); return null; })).getCode());
        assertEquals("WITHDRAWN", requests.withdraw(user.id(), version).status());
        assertEquals("WITHDRAWN", requests.withdraw(user.id(), version).status());
        assertDoesNotThrow(() -> tx.execute(ignored -> { tradeGate.requireCanStartTrade(user.id()); return null; }));
    }

    @Test
    void sessionVersionChangeBeforeAccountLockRejectsRequest() throws Exception {
        int version = jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, user.id());
        var entered = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var attempt = new TransactionTemplate(transactions).execute(ignored -> {
                jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=? FOR UPDATE",
                        Integer.class, user.id());
                var pending = worker.submit(() -> {
                    entered.countDown();
                    return assertThrows(BusinessException.class,
                            () -> requests.request(user.id(), version)).getCode();
                });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                jdbc.update("UPDATE user_accounts SET auth_version=auth_version+1 WHERE user_id=?", user.id());
                return pending;
            });
            assertNotNull(attempt);
            assertEquals(401, attempt.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_requests WHERE user_id=?",
                Long.class, user.id()));
    }
}
