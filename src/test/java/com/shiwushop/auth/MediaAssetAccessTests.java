package com.shiwushop.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;


import com.shiwushop.common.media.mapper.MediaAssetMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaAssetAccessTests {
    @Test
    void rejectsUnavailableMediaWithCallersMessage() {
        MediaAssetMapper mapper = mock(MediaAssetMapper.class);
        MediaAssetAccess access = new MediaAssetAccess(mapper);
        when(mapper.selectReadyOwnedForUpdate(5L, 9L)).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> access.requireReadyOwned(5L, 9L, "店铺标识媒体不存在或不可用"));
        assertEquals(400, error.getCode());
        assertEquals("店铺标识媒体不存在或不可用", error.getMessage());

        when(mapper.selectReadyOwnedForUpdate(5L, 9L)).thenReturn(5L);
        access.requireReadyOwned(5L, 9L, "店铺标识媒体不存在或不可用");
    }
}
