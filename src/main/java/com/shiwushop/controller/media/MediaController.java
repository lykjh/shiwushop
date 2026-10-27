package com.shiwushop.controller.media;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {
    private final MediaService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Uploaded> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return Result.success(new Uploaded(service.upload(SessionUser.requireId(request), file)));
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<byte[]> read(@PathVariable Long mediaId, HttpServletRequest request) {
        MediaService.ImageFile image = service.read(mediaId, SessionUser.currentId(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .body(image.bytes());
    }

    public record Uploaded(Long id) {}
}
