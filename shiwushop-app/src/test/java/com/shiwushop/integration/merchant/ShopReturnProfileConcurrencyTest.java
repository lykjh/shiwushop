package com.shiwushop.integration.merchant;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.merchant.service.ShopService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=2", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ShopReturnProfileConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopReturnProfileService profiles;
    @Autowired ShopService shops;
    @MockitoSpyBean MerchantShopAccess access;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void transferBeforeOwnerLockRejectsOldWrite() throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var oldOwner = accounts.create("MERCHANT", "Shop123!");
            var newOwner = accounts.create("MERCHANT", "Shop123!");
            String name = "金陵生活馆" + oldOwner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", oldOwner.id(), name);
            Long id = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            doAnswer(call -> {
                entered.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return call.callRealMethod();
            })
                    .when(access).requireOwnedForUpdate(eq(oldOwner.id()), eq(id), anyString());
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> pending = executor.submit(() -> profiles.save(id, oldOwner.id(), dto()));
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    shops.changeOwner(id, newOwner.id());
                } finally { resume.countDown();
                }
                var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                assertInstanceOf(BusinessException.class, failure.getCause());
                assertEquals(404, ((BusinessException) failure.getCause()).getCode());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM shop_return_profiles WHERE shop_id=?", Integer.class, id));
            } finally {
                resume.countDown();
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", id);
                jdbc.update("DELETE FROM shops WHERE id=?", id);
            }
        }
    }

    @Test
    void snapshotLockSerializesUpdateAndPreservesOriginal() throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("MERCHANT", "Shop123!");
            String name = "秦淮日用品店" + owner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", owner.id(), name);
            Long id = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            profiles.save(id, owner.id(), dto());
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writerEntered = new CountDownLatch(1);
            doAnswer(call -> {
                var shop = call.callRealMethod();
                writerEntered.countDown();
                return shop;
            }).when(access).requireOwnedForUpdate(eq(owner.id()), eq(id), anyString());
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<?> reader = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    var snapshot = profiles.snapshotForReturn(id);
                    locked.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    assertEquals("张明", profiles.decryptReturnDestination(snapshot).consignee());
                    return null;
                }));
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    Future<?> writer = executor.submit(() -> {
                        var d = dto();
                        d.setConsignee("李华");
                        profiles.save(id, owner.id(), d);
                    });
                    assertTrue(writerEntered.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> writer.get(300, TimeUnit.MILLISECONDS));
                    release.countDown();
                    reader.get(10, TimeUnit.SECONDS);
                    writer.get(10, TimeUnit.SECONDS);
                    assertEquals("李华", profiles.get(id, owner.id()).consignee());
                } finally {
                    release.countDown();
                }
            } finally {
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", id);
                jdbc.update("DELETE FROM shops WHERE id=?", id);
            }
        }
    }

    @Test
    void snapshotRequiresCallerTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> profiles.snapshotForReturn(1L));
    }

    @Test
    void ownerWriteHoldsShopLockUntilCommit() throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("MERCHANT", "Shop123!");
            var next = accounts.create("MERCHANT", "Shop123!");
            String name = "南京家居用品店" + owner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", owner.id(), name);
            Long id = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var transferStarted = new CountDownLatch(1);
            doAnswer(call -> {
                var shop = call.callRealMethod();
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return shop;
            }).when(access).requireOwnedForUpdate(eq(owner.id()), eq(id), anyString());
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<?> writer = executor.submit(() -> profiles.save(id, owner.id(), dto()));
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    Future<?> transfer = executor.submit(() -> {
                        transferStarted.countDown();
                        shops.changeOwner(id, next.id());
                    });
                    assertTrue(transferStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> transfer.get(300, TimeUnit.MILLISECONDS));
                    release.countDown();
                    writer.get(10, TimeUnit.SECONDS);
                    transfer.get(10, TimeUnit.SECONDS);
                    assertEquals("张明", profiles.get(id, next.id()).consignee());
                } finally {
                    release.countDown();
                }
            } finally {
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", id);
                jdbc.update("DELETE FROM shops WHERE id=?", id);
            }
        }
    }

    @Test
    void rollbackRestoresOriginalProfile() {
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("MERCHANT", "Shop123!");
            String name = "江南日用百货" + owner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", owner.id(), name);
            Long id = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            try {
                profiles.save(id, owner.id(), dto());
                var changed = dto();
                changed.setConsignee("李华");
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    profiles.save(id, owner.id(), changed);
                    status.setRollbackOnly();
                });
                assertEquals("张明", profiles.get(id, owner.id()).consignee());
            } finally {
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", id);
                jdbc.update("DELETE FROM shops WHERE id=?", id);
            }
        }
    }

    @Test
    void concurrentSavesProduceOneCompleteProfile() throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("MERCHANT", "Shop123!");
            String name = "秦淮居家用品" + owner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", owner.id(), name);
            Long id = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            var firstLocked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            doAnswer(call -> {
                var shop = call.callRealMethod();
                if (Thread.currentThread().getName().equals("first-writer")) {
                    firstLocked.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return shop;
            }).when(access).requireOwnedForUpdate(eq(owner.id()), eq(id), anyString());
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<?> first = executor.submit(() -> {
                    Thread.currentThread().setName("first-writer");
                    profiles.save(id, owner.id(), dto());
                });
                try {
                    assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
                    Future<?> second = executor.submit(() -> {
                        secondStarted.countDown();
                        var d = dto();
                        d.setConsignee("李华");
                        d.setDetail("珠江路88号");
                        profiles.save(id, owner.id(), d);
                    });
                    assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
                    release.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    second.get(10, TimeUnit.SECONDS);
                    var result = profiles.get(id, owner.id());
                    assertEquals("李华", result.consignee());
                    assertEquals("珠江路88号", result.detail());
                } finally {
                    release.countDown();
                }
            } finally {
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", id);
                jdbc.update("DELETE FROM shops WHERE id=?", id);
            }
        }
    }

    private ShopReturnProfileSaveDTO dto() {
        var d = new ShopReturnProfileSaveDTO();
        d.setContactName("王明");
        d.setContactPhone("13912345678");
        d.setConsignee("张明");
        d.setPhone("13812345678");
        d.setProvince("江苏省");
        d.setCity("南京市");
        d.setDistrict("鼓楼区");
        d.setDetail("中山北路28号");
        return d;
    }
}
