package com.shiwushop.common.media.service;

import org.springframework.web.multipart.MultipartFile;

/** 上传图片并返回资源 ID；公开读取走 MediaReadService。 */
public interface MediaService {
    Long upload(Long userId, MultipartFile file);
}
