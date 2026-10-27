package com.shiwushop.entity.media;

import lombok.Data;

@Data
public class MediaAsset {
    private Long id;
    private Long uploadedByUserId;
    private String storageKey;
    private String mimeType;
    private Long byteSize;
    private Integer width;
    private Integer height;
    private byte[] sha256;
    private String status;
}
