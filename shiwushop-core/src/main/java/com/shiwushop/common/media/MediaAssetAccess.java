package com.shiwushop.common.media;

import com.shiwushop.common.BusinessException;

import com.shiwushop.common.media.mapper.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaAssetAccess {
    private final MediaAssetMapper mediaAssetMapper;

    /** 锁定本人媒体，区分不存在和状态未就绪。 */
    public void requireReadyOwned(Long mediaId, Long userId) {
        String status = mediaAssetMapper.selectOwnedStatusForUpdate(mediaId, userId);
        if (status == null)
            throw new BusinessException(404, "媒体不存在");
        if (!status.equals("READY"))
            throw new BusinessException(409, "媒体尚未就绪");
    }

    /** 对图片行加锁并确认其已就绪且属于当前用户，供业务对象关联时使用。 */
    public void requireReadyOwned(Long mediaId, Long userId, String notFoundMessage) {
        if (mediaAssetMapper.selectReadyOwnedForUpdate(mediaId, userId) == null) {
            throw new BusinessException(400, notFoundMessage);
        }
    }

    /** 资质关联时锁定上传者的私有证照，避免将公开图片当作资质。 */
    public void requireReadyCredentialOwned(Long mediaId, Long userId) {
        if (mediaAssetMapper.selectReadyCredentialOwnedForUpdate(mediaId, userId) == null)
            throw new BusinessException(400, "资质图片不存在或不可用");
    }
}
