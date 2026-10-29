package com.shiwushop.common.media.mapper;

import com.shiwushop.common.media.entity.MediaAsset;
import org.apache.ibatis.annotations.Param;

public interface MediaAssetMapper {
    Long selectReadyOwnedForUpdate(@Param("mediaId") Long mediaId, @Param("userId") Long userId);

    int insert(MediaAsset asset);

    MediaAsset selectReady(@Param("mediaId") Long mediaId);

    boolean existsByStorageKey(@Param("storageKey") String storageKey);
}
