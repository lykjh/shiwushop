package com.shiwushop.service;

import org.springframework.web.multipart.MultipartFile;

public interface MediaService {
    Long upload(Long userId, MultipartFile file);
    ImageFile read(Long mediaId, Long currentUserId);

    record ImageFile(byte[] bytes, String mimeType, boolean owned) {}
}
