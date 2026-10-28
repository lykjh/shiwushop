package com.shiwushop.media;

import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.entity.media.MediaAsset;
import com.shiwushop.mapper.media.MediaAssetMapper;
import com.shiwushop.service.MediaService;
import com.shiwushop.service.impl.MediaServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaReadUnitTests {
    @TempDir Path root;

    @Test
    void returnsFileResourceAfterCheckingPublicVisibility() throws Exception {
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        MediaServiceImpl service = new MediaServiceImpl(assets, mock(AccountAccess.class));
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());
        String key = "d3bd6f2b-2b7b-4430-865a-4fc44875118f.png";
        Files.write(root.resolve(key), new byte[]{1, 2, 3});
        MediaAsset asset = new MediaAsset();
        asset.setUploadedByUserId(1L);
        asset.setStorageKey(key);
        asset.setMimeType("image/png");
        when(assets.selectReady(7L)).thenReturn(asset);
        when(assets.isPubliclyReferenced(7L)).thenReturn(true);

        MediaService.ImageFile result = service.read(7L, null);

        assertInstanceOf(FileSystemResource.class, result.resource());
        assertEquals(key, result.etag());
        assertEquals(3, result.resource().contentLength());
    }
}
