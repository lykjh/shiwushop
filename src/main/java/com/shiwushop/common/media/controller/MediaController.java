package com.shiwushop.common.media.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.media.service.MediaReadService;
import com.shiwushop.common.media.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 统一承载 /media 入口；上传处理与读取授权分别交给对应服务。 */
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {
    private final MediaService uploadService;
    private final MediaReadService readService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Uploaded> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return Result.success(new Uploaded(uploadService.upload(SessionUser.requireId(request), file)));
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<Resource> read(@PathVariable Long mediaId, HttpServletRequest request) {
        MediaReadService.ImageFile image = readService.read(mediaId, SessionUser.currentId(request));
        return ResponseEntity.ok()
                .cacheControl(image.owned() ? CacheControl.noStore() : CacheControl.noCache())
                .eTag(image.etag())
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .body(image.resource());
    }

    public record Uploaded(Long id) {}
}
