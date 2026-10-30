package com.shiwushop.media;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.media.entity.MediaAsset;
import com.shiwushop.common.media.PublicMediaReference;
import com.shiwushop.common.media.mapper.MediaAssetMapper;
import com.shiwushop.common.media.service.MediaReadService;
import com.shiwushop.common.media.service.impl.MediaReadServiceImpl;
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
        PublicMediaReference references = mock(PublicMediaReference.class);
        MediaReadServiceImpl service = new MediaReadServiceImpl(assets, references, mock(AccountAccess.class));
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());
        String key = "d3bd6f2b-2b7b-4430-865a-4fc44875118f.png";
        Files.write(root.resolve(key), new byte[]{1, 2, 3});
        MediaAsset asset = new MediaAsset();
        asset.setUploadedByUserId(1L);
        asset.setStorageKey(key);
        asset.setMimeType("image/png");
        when(assets.selectReady(7L)).thenReturn(asset);
        when(references.isPubliclyReferenced(7L)).thenReturn(true);

        MediaReadService.ImageFile result = service.read(7L, null);

        assertInstanceOf(FileSystemResource.class, result.resource());
        assertEquals(key, result.etag());
        assertEquals(3, result.resource().contentLength());
    }
}
