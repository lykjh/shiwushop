package com.shiwushop.common.media.service;

import org.springframework.web.multipart.MultipartFile;

/** 上传图片并返回资源 ID；公开读取走 MediaReadService。 */
public interface MediaService {
    /** 验证、存储图片并返回持久化媒体 ID。 */
    Long upload(Long userId, MultipartFile file);

    /** 资质图片始终保持私有用途，不能被头像、商品等公开对象引用。 */
    Long uploadCredential(Long userId, MultipartFile file);
}
