package com.shiwushop.common.media.service.impl;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.PublicMediaReference;
import com.shiwushop.common.media.entity.MediaAsset;
import com.shiwushop.common.media.mapper.MediaAssetMapper;
import com.shiwushop.common.media.service.MediaReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 图片读取只负责访问授权和文件校验，上传时的解码与落盘由 MediaServiceImpl 负责。 */
@Service
@RequiredArgsConstructor
public class MediaReadServiceImpl implements MediaReadService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final MediaAssetMapper assets;
    private final PublicMediaReference publicReferences;
    private final AccountAccess accounts;

    @Value("${shop.media.root:./local-data/media}")
    private String storageRoot;

    @Override
    public ImageFile read(Long mediaId, Long currentUserId) {
        if (mediaId == null || mediaId <= 0) throw new BusinessException(404, "图片不存在");
        MediaAsset asset = assets.selectReady(mediaId);
        if (asset == null) throw new BusinessException(404, "图片不存在");
        boolean owner = currentUserId != null && currentUserId.equals(asset.getUploadedByUserId());
        if (owner) accounts.checkActive(currentUserId);
        // 非上传者只能读取已被公开商品、店铺、品牌或评价引用的图片。
        if (!owner && !publicReferences.isPubliclyReferenced(mediaId)) throw new BusinessException(404, "图片不存在");
        if (!asset.getStorageKey().matches("[0-9a-fA-F-]{36}\\.(png|jpg)"))
            throw new BusinessException(503, "图片暂时无法读取");
        Path root = Path.of(storageRoot).toAbsolutePath().normalize();
        Path path = root.resolve(asset.getStorageKey()).normalize();
        if (!path.getParent().equals(root)) throw new BusinessException(503, "图片暂时无法读取");
        try {
            long size = Files.size(path);
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                    || size == 0 || size > MAX_BYTES)
                throw new BusinessException(503, "图片暂时无法读取");
            // 文件名由上传时生成且不再修改，可作为条件请求的版本标识。
            return new ImageFile(new FileSystemResource(path), asset.getMimeType(), owner, asset.getStorageKey());
        } catch (IOException e) {
            throw new BusinessException(503, "图片暂时无法读取", e);
        }
    }
}
