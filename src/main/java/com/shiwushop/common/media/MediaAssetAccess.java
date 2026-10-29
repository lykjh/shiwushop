package com.shiwushop.common.media;

import com.shiwushop.common.BusinessException;


import com.shiwushop.common.media.mapper.MediaAssetMapper;
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
