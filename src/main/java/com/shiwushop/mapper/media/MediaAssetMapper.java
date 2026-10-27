package com.shiwushop.mapper.media;

import com.shiwushop.entity.media.MediaAsset;
import org.apache.ibatis.annotations.Param;

public interface MediaAssetMapper {
    Long selectReadyOwnedForUpdate(@Param("mediaId") Long mediaId, @Param("userId") Long userId);

    int insert(MediaAsset asset);

    MediaAsset selectReady(@Param("mediaId") Long mediaId);

    boolean isPubliclyReferenced(@Param("mediaId") Long mediaId);
}
