package com.shiwushop.media;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.user.LoginTestRequest;
import com.shiwushop.account.service.UserService;
import com.shiwushop.common.media.service.MediaService;
import com.shiwushop.common.media.MediaOrphanCleanup;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "shop.media.root=./target/media-security-tests")
class MediaSecurityTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private CaptchaProofStore proofs;
    @Autowired private MediaService mediaService;
    @Autowired private MediaOrphanCleanup orphanCleanup;
    @Autowired private JdbcTemplate jdbc;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> mediaIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();
    private final List<Long> shopIds = new ArrayList<>();

    @Test
    void decodesAndReencodesRealImageAndProtectsUnreferencedFile() throws Exception {
        MockHttpSession owner = account();
        MockHttpSession other = account();
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x123456);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(image, "png", source);
        byte[] input = source.toByteArray();
        String response = mvc.perform(multipart("/media").file(new MockMultipartFile(
                        "file", "misleading.jpg", "text/plain", input)).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        Long mediaId = ((Number) JsonPath.read(response, "$.data.id")).longValue();
        mediaIds.add(mediaId);
        assertEquals("image/png", jdbc.queryForObject("SELECT mime_type FROM media_assets WHERE id=?", String.class, mediaId));
        byte[] output = mvc.perform(get("/media/{id}", mediaId).session(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(output));
        assertNotNull(decoded);
        assertEquals(0x123456, decoded.getRGB(0, 0) & 0xffffff);
        mvc.perform(get("/media/{id}", mediaId)).andExpect(status().isNotFound());
        mvc.perform(get("/media/{id}", mediaId).session(other)).andExpect(status().isNotFound());

        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", userIds.getFirst(), "媒体店铺" + suffix);
        Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, "媒体店铺" + suffix);
        shopIds.add(shopId);
        jdbc.update("INSERT INTO categories (name,level,visible,sort_order) VALUES (?,1,1,0)", "媒体分类" + suffix);
        Long categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, "媒体分类" + suffix);
        categoryIds.add(categoryId);
        jdbc.update("INSERT INTO products (shop_id,category_id,name,status) VALUES (?,?,?,'ON_SALE')",
                shopId, categoryId, "媒体商品" + suffix);
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, "媒体商品" + suffix);
        productIds.add(productId);
        jdbc.update("INSERT INTO product_images (product_id,media_id,sort_order,is_cover) VALUES (?,?,0,1)",
                productId, mediaId);
        String etag = mvc.perform(get("/media/{id}", mediaId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/media/{id}", mediaId).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        jdbc.update("DELETE FROM product_images WHERE product_id=?", productId);
        mvc.perform(get("/media/{id}", mediaId).header("If-None-Match", etag))
                .andExpect(status().isNotFound());
        jdbc.update("INSERT INTO product_images (product_id,media_id,sort_order,is_cover) VALUES (?,?,0,1)",
                productId, mediaId);
        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?", productId);
        mvc.perform(get("/media/{id}", mediaId).header("If-None-Match", etag))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDisguisedAndOversizedImageWithoutMediaRow() throws Exception {
        MockHttpSession owner = account();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class);
        mvc.perform(multipart("/media").file(new MockMultipartFile("file", "fake.png", "image/png",
                        "<script>alert(1)</script>".getBytes())).session(owner))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/media").file(new MockMultipartFile("file", "large.png", "image/png",
                        new byte[10 * 1024 * 1024 + 1])).session(owner))
                .andExpect(status().isPayloadTooLarge());
        ByteArrayOutputStream small = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", small);
        byte[] pixelBomb = small.toByteArray();
        ByteBuffer.wrap(pixelBomb, 16, 8).putInt(5000).putInt(5000);
        CRC32 crc = new CRC32();
        crc.update(pixelBomb, 12, 17);
        ByteBuffer.wrap(pixelBomb, 29, 4).putInt((int) crc.getValue());
        mvc.perform(multipart("/media").file(new MockMultipartFile("file", "bomb.png", "image/png",
                        pixelBomb)).session(owner))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
    }

    @Test
    void storageFailureReturns503WithoutCreatingMediaRow() throws Exception {
        MockHttpSession owner = account();
        Path blocker = Path.of("target/media-security-tests/storage-blocker");
        Files.createDirectories(blocker.getParent());
        Files.writeString(blocker, "file, not directory");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image);
        Object originalRoot = ReflectionTestUtils.getField(mediaService, "storageRoot");
        try {
            ReflectionTestUtils.setField(mediaService, "storageRoot", blocker.resolve("images").toString());
            mvc.perform(multipart("/media").file(new MockMultipartFile("file", "test.png", "image/png",
                            image.toByteArray())).session(owner))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("图片暂时无法保存"));
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        } finally {
            ReflectionTestUtils.setField(mediaService, "storageRoot", originalRoot);
            Files.deleteIfExists(blocker);
        }
    }

    @Test
    void cleanupKeepsDatabaseBackedImageAndRemovesOldOrphan() throws Exception {
        account();
        Path root = Path.of("target/media-security-tests");
        Files.createDirectories(root);
        String retainedKey = UUID.randomUUID() + ".png";
        String orphanKey = UUID.randomUUID() + ".png";
        Path retained = Files.writeString(root.resolve(retainedKey), "retained");
        Path orphan = Files.writeString(root.resolve(orphanKey), "orphan");
        FileTime old = FileTime.from(Instant.now().minus(48, ChronoUnit.HOURS));
        Files.setLastModifiedTime(retained, old);
        Files.setLastModifiedTime(orphan, old);
        jdbc.update("INSERT INTO media_assets (uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status) "
                        + "VALUES (?,?,'image/png',8,1,1,UNHEX(SHA2(?,256)),'READY')",
                userIds.getFirst(), retainedKey, retainedKey);
        mediaIds.add(jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_key=?", Long.class, retainedKey));

        orphanCleanup.clean();

        assertTrue(Files.exists(retained));
        assertFalse(Files.exists(orphan));
    }

    private MockHttpSession account() throws Exception {
        String name = "media_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(name, "Shop123!", null));
        userIds.add(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name));
        return (MockHttpSession) mvc.perform(LoginTestRequest.login(proofs, name, "Shop123!"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Long id : productIds) {
            jdbc.update("DELETE FROM product_images WHERE product_id=?", id);
            jdbc.update("DELETE FROM products WHERE id=?", id);
        }
        for (Long id : categoryIds) jdbc.update("DELETE FROM categories WHERE id=?", id);
        for (Long id : shopIds) jdbc.update("DELETE FROM shops WHERE id=?", id);
        for (Long id : mediaIds) {
            String key = jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id=?", String.class, id);
            jdbc.update("DELETE FROM media_assets WHERE id=?", id);
            Files.deleteIfExists(Path.of("target/media-security-tests", key));
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
    }
}
