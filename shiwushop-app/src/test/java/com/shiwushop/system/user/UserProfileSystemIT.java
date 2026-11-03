package com.shiwushop.system.user;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class UserProfileSystemIT {
    @Test void uploadedAvatarCanBeSetAndAnotherUsersMediaIsRejected() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        Path root = Path.of(Objects.requireNonNull(System.getProperty("system.media-root"),
                "必须指定独立实例的图片目录")).toAbsolutePath().normalize();
        assertTrue(root.startsWith(Path.of("target").toAbsolutePath().normalize()));
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("USER", "Shop123!");
            var other = accounts.create("USER", "Shop456!");
            try (var ownerClient = CommerceFixture.login(base, owner);
                 var otherClient = CommerceFixture.login(base, other)) {
                try {
                    var image = new ByteArrayOutputStream();
                    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", image);
                    var ownUpload = ownerClient.upload("/media", image.toByteArray(), "头像.png", "image/png");
                    var otherUpload = otherClient.upload("/media", image.toByteArray(), "头像.png", "image/png");
                    assertEquals(200, ownUpload.statusCode(), ownUpload.body());
                    assertEquals(200, otherUpload.statusCode(), otherUpload.body());
                    long ownMedia = json.readTree(ownUpload.body()).path("data").path("id").asLong();
                    long otherMedia = json.readTree(otherUpload.body()).path("data").path("id").asLong();
                    var updated = ownerClient.request("PUT", "/users/profile", Map.of("nickname", "林晓", "avatarMediaId", ownMedia));
                    assertEquals(200, updated.statusCode(), updated.body());
                    var profile = ownerClient.get("/users/profile");
                    assertEquals(200, profile.statusCode(), profile.body());
                    assertEquals(ownMedia, json.readTree(profile.body()).path("data").path("avatarMediaId").asLong());
                    assertEquals(404, ownerClient.request("PUT", "/users/profile",
                            Map.of("nickname", "林晓", "avatarMediaId", otherMedia)).statusCode());
                    assertEquals(ownMedia, json.readTree(ownerClient.get("/users/profile").body()).path("data").path("avatarMediaId").asLong());
                    var clear = new HashMap<String, Object>();
                    clear.put("nickname", "林晓");
                    clear.put("avatarMediaId", null);
                    assertEquals(200, ownerClient.request("PUT", "/users/profile", clear).statusCode());
                } finally {
                    jdbc.update("UPDATE users SET avatar_media_id=NULL WHERE id IN (?,?)", owner.id(), other.id());
                    for (String key : jdbc.queryForList("SELECT storage_key FROM media_assets WHERE uploaded_by_user_id IN (?,?)",
                            String.class, owner.id(), other.id())) {
                        Path file = root.resolve(key).normalize();
                        assertTrue(file.startsWith(root));
                        Files.deleteIfExists(file);
                    }
                    jdbc.update("DELETE FROM media_assets WHERE uploaded_by_user_id IN (?,?)", owner.id(), other.id());
                }
            }
        }
    }
}
