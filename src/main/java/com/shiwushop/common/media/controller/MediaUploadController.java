package com.shiwushop.common.media.controller;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.media.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 上传入口只处理登录用户提交的文件，图片公开读取有独立入口。 */
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaUploadController {
    private final MediaService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Uploaded> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return Result.success(new Uploaded(service.upload(SessionUser.requireId(request), file)));
    }

    public record Uploaded(Long id) {}
}
