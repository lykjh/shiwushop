package com.shiwushop.common.media.entity;

import lombok.Data;

@Data
public class MediaAsset {
    /** 媒体资源 ID。 */
    private Long id;
    /** 上传文件的用户 ID。 */
    private Long uploadedByUserId;
    /** 文件内部存储定位键。 */
    private String storageKey;
    /** 文件媒体类型。 */
    private String mimeType;
    /** 文件大小，单位字节。 */
    private Long byteSize;
    /** 图片宽度，单位像素。 */
    private Integer width;
    /** 图片高度，单位像素。 */
    private Integer height;
    /** 文件内容的 SHA-256 摘要。 */
    private byte[] sha256;
    /** 媒体状态：STAGED 暂存、READY 可用、DELETED 已删除。 */
    private String status;
    /** 媒体用途：PUBLIC 公开图片、CREDENTIAL 入驻证照。 */
    private String purpose;
}
