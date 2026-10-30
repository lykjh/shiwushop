package com.shiwushop.common.media.controller;

import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.media.service.MediaReadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 读取入口保留原路径和缓存头；授权与文件检查交由读取服务。 */
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaReadController {
    private final MediaReadService service;

    @GetMapping("/{mediaId}")
    public ResponseEntity<Resource> read(@PathVariable Long mediaId, HttpServletRequest request) {
        MediaReadService.ImageFile image = service.read(mediaId, SessionUser.currentId(request));
        return ResponseEntity.ok()
                .cacheControl(image.owned() ? CacheControl.noStore() : CacheControl.noCache())
                .eTag(image.etag())
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .body(image.resource());
    }
}
