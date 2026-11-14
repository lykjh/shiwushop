package com.shiwushop.common.media.service;

import org.springframework.core.io.Resource;

/** 读取图片前校验所有者或公开引用，不参与上传和重新编码。 */
public interface MediaReadService {
    /** 核对图片所有权或公开引用后返回文件资源与响应元数据。 */
    ImageFile read(Long mediaId, Long currentUserId);

    /** 已验证管理员读取申请中的私有资质图片。 */
    ImageFile readCredentialForReview(Long mediaId, Long reviewerId);

    /** 管理员读取已由商品审核入口核对关联的待审商品图片。 */
    ImageFile readProductImageForReview(Long mediaId, Long reviewerId);

    /**
     * @param resource 当前请求涉及的资源标识
     * @param mimeType 文件的媒体类型
     * @param owned 当前用户是否拥有该业务记录
     * @param etag 文件内容的 HTTP 缓存标识
     */
    record ImageFile(Resource resource, String mimeType, boolean owned, String etag) {
    }
}
