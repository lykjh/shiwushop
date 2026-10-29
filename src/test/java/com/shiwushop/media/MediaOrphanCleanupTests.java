package com.shiwushop.media;

import com.shiwushop.common.media.mapper.MediaAssetMapper;
import com.shiwushop.common.media.MediaOrphanCleanup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaOrphanCleanupTests {
    @TempDir Path root;

    @Test
    void removesOnlyOldUnreferencedImagesAndUploadTemps() throws Exception {
        String orphanName = "d3bd6f2b-2b7b-4430-865a-4fc44875118f.png";
        String referencedName = "b8e4d443-e52a-4547-bfe6-e3bcc1c7d49e.jpg";
        String youngName = "0e45a240-4ed9-4ce0-b6e9-d2e55e424069.png";
        Path orphan = oldFile(orphanName);
        Path referenced = oldFile(referencedName);
        Path temp = oldFile("source-123.tmp");
        Path unrelated = oldFile("notes.txt");
        Path young = Files.writeString(root.resolve(youngName), "young");
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        when(assets.existsByStorageKey(referencedName)).thenReturn(true);

        cleanup(assets).clean();

        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(temp));
        assertTrue(Files.exists(referenced));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void keepsImageWhenDatabaseCheckFails() throws Exception {
        String name = "d3bd6f2b-2b7b-4430-865a-4fc44875118f.png";
        Path image = oldFile(name);
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        when(assets.existsByStorageKey(name)).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> cleanup(assets).clean());
        assertTrue(Files.exists(image));
    }

    private MediaOrphanCleanup cleanup(MediaAssetMapper assets) {
        MediaOrphanCleanup cleanup = new MediaOrphanCleanup(assets);
        ReflectionTestUtils.setField(cleanup, "storageRoot", root.toString());
        ReflectionTestUtils.setField(cleanup, "retentionHours", 24L);
        return cleanup;
    }

    private Path oldFile(String name) throws Exception {
        Path file = Files.writeString(root.resolve(name), "old");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(48, ChronoUnit.HOURS)));
        return file;
    }
}
