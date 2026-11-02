package com.shiwushop.common.media.service;

import org.springframework.core.io.Resource;

/** 读取图片前校验所有者或公开引用，不参与上传和重新编码。 */
public interface MediaReadService {
    /** 核对图片所有权或公开引用后返回文件资源与响应元数据。 */
    ImageFile read(Long mediaId, Long currentUserId);

    record ImageFile(Resource resource, String mimeType, boolean owned, String etag) {}
}
