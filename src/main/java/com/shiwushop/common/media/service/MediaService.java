package com.shiwushop.common.media.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

public interface MediaService {
    Long upload(Long userId, MultipartFile file);
    ImageFile read(Long mediaId, Long currentUserId);

    record ImageFile(Resource resource, String mimeType, boolean owned, String etag) {}
}
