package com.shiwushop.catalog.mapper.media;

import com.shiwushop.common.media.PublicMediaReference;
import org.apache.ibatis.annotations.Param;

public interface CatalogMediaReferenceMapper extends PublicMediaReference {
    @Override
    boolean isPubliclyReferenced(@Param("mediaId") Long mediaId);
}
