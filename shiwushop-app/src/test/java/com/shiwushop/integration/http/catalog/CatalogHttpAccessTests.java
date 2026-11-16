package com.shiwushop.integration.http.catalog;

import com.jayway.jsonpath.JsonPath;
import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18123", "shop.media.root=./target/catalog-http-media"})
class CatalogHttpAccessTests {
    @Autowired JdbcTemplate jdbc;
    TestAccounts accounts;
    final List<Long> products = new ArrayList<>();
    final List<Long> skus = new ArrayList<>();
    final List<Long> shops = new ArrayList<>();
    final List<Long> categories = new ArrayList<>();
    final List<Long> media = new ArrayList<>();
    final List<Long> applications = new ArrayList<>();

    @BeforeEach
    void prepare() {
        accounts = new TestAccounts(jdbc);
    }

    @Test
    void adminStatusRequiresRealSessionRoleAndCsrf() throws Exception {
        var owner = accounts.create("MERCHANT", "Shop123!");
        var admin = accounts.create("ADMIN", "Shop123!");
        long productId = product(owner.id());
        try (var anonymous = new HttpAccountClient(base());
             var merchant = login(owner);
             var reviewer = login(admin)) {
            anonymous.csrf();
            assertEquals(401, anonymous.request("PUT", "/admin/products/" + productId + "/status",
                    Map.of("status", "OFF_SALE")).statusCode());
            assertEquals(403, merchant.request("PUT", "/admin/products/" + productId + "/status",
                    Map.of("status", "OFF_SALE")).statusCode());
            assertEquals(403, reviewer.request("PUT", "/admin/products/" + productId + "/status",
                    Map.of("status", "OFF_SALE"), false, Map.of()).statusCode());
            var stopped = reviewer.request("PUT", "/admin/products/" + productId + "/status",
                    Map.of("status", "OFF_SALE"));
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertEquals("OFF_SALE", jdbc.queryForObject(
                    "SELECT status FROM products WHERE id=?", String.class, productId));
        }
    }

    @Test
    void privateImageRequiresUploaderSession() throws Exception {
        var owner = accounts.create("MERCHANT", "Shop123!");
        var other = accounts.create("USER", "Shop123!");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        try (var anonymous = new HttpAccountClient(base());
             var uploader = login(owner);
             var stranger = login(other)) {
            var upload = uploader.upload("/media", image.toByteArray(), "proof.png", "image/png");
            assertEquals(200, upload.statusCode(), upload.body());
            long id = ((Number) JsonPath.read(upload.body(), "$.data.id")).longValue();
            media.add(id);
            assertEquals(200, uploader.get("/media/" + id).statusCode());
            assertEquals(404, stranger.get("/media/" + id).statusCode());
            assertEquals(404, anonymous.get("/media/" + id).statusCode());
        }
    }

    @Test
    void stoppedSkuKeepsStockAndCannotResumeWhileProductIsOffSale() throws Exception {
        var owner = accounts.create("MERCHANT", "Shop123!");
        var other = accounts.create("USER", "Shop123!");
        long productId = product(owner.id());
        jdbc.update("INSERT INTO skus(product_id,option_signature,price,stock) VALUES (?,REPEAT('a',64),19.00,7)",
                productId);
        long skuId = jdbc.queryForObject("SELECT id FROM skus WHERE product_id=?", Long.class, productId);
        skus.add(skuId);
        String statusPath = "/merchant/products/" + productId + "/skus/" + skuId + "/status";
        try (var merchant = login(owner); var stranger = login(other)) {
            assertEquals(404, stranger.request("PUT", statusPath, Map.of("status", "STOPPED")).statusCode());
            var stopped = merchant.request("PUT", statusPath, Map.of("status", "STOPPED"));
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertEquals("STOPPED", jdbc.queryForObject("SELECT status FROM skus WHERE id=?", String.class, skuId));
            assertEquals(7, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, skuId));
            assertEquals(200, merchant.request("PUT", statusPath,
                    Map.of("status", "STOPPED")).statusCode());
            jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?", productId);
            assertEquals(409, merchant.request("PUT", statusPath, Map.of("status", "ACTIVE")).statusCode());
        }
    }

    @Test
    void applicationApprovalStaffRevocationAndQualificationWithdrawal() throws Exception {
        var applicant = accounts.create("USER", "Shop123!");
        var staff = accounts.create("USER", "Shop123!");
        var admin = accounts.create("ADMIN", "Shop123!");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        String name = "入驻店铺-" + UUID.randomUUID();
        try (var owner = login(applicant);
             var employee = login(staff);
             var reviewer = login(admin)) {
            var upload = owner.upload("/media/credentials", image.toByteArray(), "identity.png", "image/png");
            assertEquals(200, upload.statusCode(), upload.body());
            long mediaId = ((Number) JsonPath.read(upload.body(), "$.data.id")).longValue();
            media.add(mediaId);
            var created = owner.post("/merchant/applications", Map.of(
                    "shopName", name, "businessType", "PERSONAL", "documents",
                    List.of(Map.of("documentType", "IDENTITY", "mediaId", mediaId))));
            assertEquals(200, created.statusCode(), created.body());
            long applicationId = ((Number) JsonPath.read(created.body(), "$.data.id")).longValue();
            applications.add(applicationId);
            assertEquals(404, employee.get("/merchant/applications/" + applicationId).statusCode());
            assertEquals(200, owner.post("/merchant/applications/" + applicationId + "/submit", Map.of()).statusCode());
            assertEquals(200, reviewer.get("/admin/merchant-applications/" + applicationId
                    + "/documents/IDENTITY").statusCode());
            assertEquals(404, employee.get("/media/" + mediaId).statusCode());
            String decision = "/admin/merchant-applications/" + applicationId + "/decision";
            var approved = reviewer.post(decision, Map.of("version", 1, "approved", true));
            assertEquals(200, approved.statusCode(), approved.body());
            long shopId = ((Number) JsonPath.read(approved.body(), "$.data.shopId")).longValue();
            shops.add(shopId);
            assertEquals(200, reviewer.post(decision, Map.of("version", 1, "approved", true)).statusCode());
            assertEquals(409, reviewer.post(decision,
                    Map.of("version", 1, "approved", false, "reason", "重审")).statusCode());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merchant_application_reviews WHERE application_id=?", Integer.class,
                    applicationId));
            assertEquals("MERCHANT", jdbc.queryForObject(
                    "SELECT role FROM user_accounts WHERE user_id=?", String.class, applicant.id()));
            String grantPath = "/merchant/shops/" + shopId + "/staff/" + staff.id();
            assertEquals(200, owner.request("PUT", grantPath, Map.of("roles", List.of("CATALOG"))).statusCode());
            assertEquals(404, employee.request("PUT", grantPath,
                    Map.of("roles", List.of("INVENTORY"))).statusCode());
            assertEquals(200, owner.request("DELETE", grantPath, Map.of()).statusCode());
            assertEquals("REVOKED", jdbc.queryForObject(
                    "SELECT status FROM shop_staff_grants WHERE shop_id=? AND user_id=?", String.class,
                    shopId, staff.id()));
            assertEquals(200, reviewer.request("PUT", "/admin/shops/" + shopId + "/status",
                    Map.of("enabled", 1)).statusCode());
            assertEquals(200, owner.post("/merchant/applications/" + applicationId + "/withdraw",
                    Map.of()).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT enabled FROM shops WHERE id=?", Integer.class, shopId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT qualification_revoked FROM shops WHERE id=?", Integer.class, shopId));
        }
    }

    private String base() {
        return "http://127.0.0.1:18123";
    }

    private HttpAccountClient login(TestAccounts.Account account) throws Exception {
        var client = new HttpAccountClient(base());
        client.csrf();
        var result = client.post("/users/login",
                Map.of("username", account.username(), "password", account.password()));
        assertEquals(200, result.statusCode(), result.body());
        client.csrf();
        return client;
    }

    private long product(long ownerId) {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", ownerId, "状态店铺-" + suffix);
        long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, "状态店铺-" + suffix);
        shops.add(shopId);
        jdbc.update("INSERT INTO categories(name,level,visible,sort_order) VALUES (?,1,1,0)", "状态分类-" + suffix);
        long categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, "状态分类-" + suffix);
        categories.add(categoryId);
        jdbc.update("INSERT INTO products(shop_id,category_id,name,status) VALUES (?,?,?,'ON_SALE')",
                shopId, categoryId, "状态商品-" + suffix);
        long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, "状态商品-" + suffix);
        products.add(productId);
        return productId;
    }

    @AfterEach
    void cleanup() throws Exception {
        for (long id : skus) jdbc.update("DELETE FROM skus WHERE id=?", id);
        for (long id : products) {
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        for (long id : shops) jdbc.update("DELETE FROM shop_staff_grants WHERE shop_id=?", id);
        for (long id : applications) {
            jdbc.update("DELETE FROM merchant_application_reviews WHERE application_id=?", id);
            jdbc.update("DELETE FROM merchant_application_documents WHERE application_id=?", id);
            jdbc.update("DELETE FROM merchant_applications WHERE id=?", id);
        }
        for (long id : shops) {
            jdbc.update("DELETE FROM shops WHERE id=?", id);
        }
        for (long id : categories) jdbc.update("DELETE FROM categories WHERE id=?", id);
        for (long id : media) {
            String key = jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id=?", String.class, id);
            jdbc.update("DELETE FROM media_assets WHERE id=?", id);
            Files.deleteIfExists(Path.of("target/catalog-http-media", key));
        }
        accounts.close();
    }
}
