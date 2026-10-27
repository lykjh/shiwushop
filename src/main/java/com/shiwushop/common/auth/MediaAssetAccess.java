package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;


import com.shiwushop.mapper.media.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaAssetAccess {
    private final MediaAssetMapper mediaAssetMapper;

    public void requireReadyOwned(Long mediaId, Long userId, String notFoundMessage) {
        if (mediaAssetMapper.selectReadyOwnedForUpdate(mediaId, userId) == null) {
            throw new BusinessException(400, notFoundMessage);
        }
    }
}
