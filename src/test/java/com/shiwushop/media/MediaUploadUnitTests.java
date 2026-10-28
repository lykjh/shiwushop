package com.shiwushop.media;

import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.entity.media.MediaAsset;
import com.shiwushop.mapper.media.MediaAssetMapper;
import com.shiwushop.service.impl.MediaServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadUnitTests {
    @TempDir Path root;

    @Test
    void streamsOriginalWithoutMaterializingMultipartBytes() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] original = encoded.toByteArray();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) original.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(original));
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        doAnswer(invocation -> {
            ((MediaAsset) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(assets).insert(any(MediaAsset.class));
        MediaServiceImpl service = new MediaServiceImpl(assets, mock(AccountAccess.class));
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());

        assertEquals(7L, service.upload(1L, file));

        verify(file, never()).getBytes();
        var asset = org.mockito.ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).insert(asset.capture());
        assertEquals(original.length, asset.getValue().getByteSize());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(original), asset.getValue().getSha256());
        try (var files = Files.list(root)) {
            assertEquals(1, files.count()); // 原图暂存文件已清理，仅保留最终图片。
        }
    }

    @Test
    void rejectsActualBytesBeyondLimitEvenWhenReportedSizeIsSmall() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]));
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        MediaServiceImpl service = new MediaServiceImpl(assets, mock(AccountAccess.class));
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());

        assertEquals(413, assertThrows(BusinessException.class, () -> service.upload(1L, file)).getCode());
        verify(assets, never()).insert(any(MediaAsset.class));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
}
