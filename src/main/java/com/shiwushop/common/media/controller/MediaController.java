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

    /**
     * 上传一张图片。先从 Session 取得登录用户 ID，再交给上传服务验证并保存文件。
     * 返回的 ID 供商品等业务在后续请求中引用这张图片。
     *
     * @param file multipart 请求中的图片文件
     * @param request 当前请求，用于确认上传者身份
     * @return 新图片的 ID
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Uploaded> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return Result.success(new Uploaded(uploadService.upload(SessionUser.requireId(request), file)));
    }

    /**
     * 按 ID 读取图片。读取服务根据当前用户和图片归属决定是否允许访问。
     * 自有图片禁止缓存；公开图片允许客户端使用 ETag 做条件请求。
     *
     * @param mediaId 图片 ID
     * @param request 当前请求，用于取得可选的登录用户 ID
     * @return 图片内容、媒体类型和缓存响应头
     */
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
