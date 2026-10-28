package com.shiwushop.service.impl;

import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.entity.media.MediaAsset;
import com.shiwushop.mapper.media.MediaAssetMapper;
import com.shiwushop.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;
    private final MediaAssetMapper assets;
    private final AccountAccess accounts;
    private final Semaphore uploadSlots = new Semaphore(2);

    @Value("${shop.media.root:./local-data/media}")
    private String storageRoot;

    @Override
    public Long upload(Long userId, MultipartFile file) {
        accounts.checkActive(userId);
        if (file == null || file.isEmpty()) throw new BusinessException(400, "图片不能为空");
        if (file.getSize() > MAX_BYTES) throw new BusinessException(413, "图片不能超过 10 MiB");
        if (!uploadSlots.tryAcquire()) throw new BusinessException(429, "图片处理繁忙，请稍后重试");
        Path originalFile = null;
        Path temporary = null;
        Path destination = null;
        boolean saved = false;
        try {
            Path root = Path.of(storageRoot).toAbsolutePath().normalize();
            Files.createDirectories(root);
            originalFile = Files.createTempFile(root, "source-", ".tmp");
            MessageDigest digest = sha256();
            long originalSize = 0;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(originalFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    originalSize += read;
                    if (originalSize > MAX_BYTES) throw new BusinessException(413, "图片不能超过 10 MiB");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (originalSize == 0) throw new BusinessException(400, "图片不能为空");
            // 以实际解码结果为准并重新编码，避免信任文件名、Content-Type 或原始文件内容。
            DecodedImage decoded = decode(originalFile);
            temporary = Files.createTempFile(root, "upload-", ".tmp");
            if (!ImageIO.write(decoded.image(), decoded.format(), temporary.toFile()))
                throw new BusinessException(400, "图片格式不支持");
            long encodedSize = Files.size(temporary);
            if (encodedSize == 0 || encodedSize > MAX_BYTES)
                throw new BusinessException(413, "处理后的图片不能超过 10 MiB");
            String key = UUID.randomUUID() + "." + decoded.format();
            destination = root.resolve(key);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            MediaAsset asset = new MediaAsset();
            asset.setUploadedByUserId(userId);
            asset.setStorageKey(key);
            asset.setMimeType(decoded.mimeType());
            asset.setByteSize(originalSize);
            asset.setWidth(decoded.image().getWidth());
            asset.setHeight(decoded.image().getHeight());
            asset.setSha256(digest.digest());
            asset.setStatus("READY");
            assets.insert(asset);
            saved = true;
            return asset.getId();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(503, "图片暂时无法保存", e);
        } finally {
            // 原子移动先于数据库写入；写库失败时清理已经落盘的文件。
            if (originalFile != null) deleteQuietly(originalFile);
            if (temporary != null) deleteQuietly(temporary);
            if (destination != null && !saved) {
                deleteQuietly(destination);
            }
            uploadSlots.release();
        }
    }

    @Override
    public ImageFile read(Long mediaId, Long currentUserId) {
        if (mediaId == null || mediaId <= 0) throw new BusinessException(404, "图片不存在");
        MediaAsset asset = assets.selectReady(mediaId);
        if (asset == null) throw new BusinessException(404, "图片不存在");
        boolean owner = currentUserId != null && currentUserId.equals(asset.getUploadedByUserId());
        if (owner) accounts.checkActive(currentUserId);
        // 非上传者只能读取已被公开商品、店铺、品牌或评价引用的图片。
        if (!owner && !assets.isPubliclyReferenced(mediaId)) throw new BusinessException(404, "图片不存在");
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

    private static DecodedImage decode(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new BusinessException(400, "图片格式不支持");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(400, "图片格式不支持");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String actual = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String format = switch (actual) {
                    case "jpeg", "jpg" -> "jpg";
                    case "png" -> "png";
                    default -> throw new BusinessException(400, "仅支持 JPEG 和 PNG 图片");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS)
                    throw new BusinessException(413, "图片像素不能超过 2000 万");
                BufferedImage image = reader.read(0);
                if (image == null) throw new BusinessException(400, "图片内容无效");
                if (format.equals("jpg")) {
                    BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = rgb.createGraphics();
                    try {
                        graphics.setColor(java.awt.Color.WHITE);
                        graphics.fillRect(0, 0, width, height);
                        graphics.drawImage(image, 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    image = rgb;
                }
                return new DecodedImage(image, format, format.equals("jpg") ? "image/jpeg" : "image/png");
            } catch (BusinessException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new BusinessException(400, "图片内容无效", e);
            } finally {
                reader.dispose();
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删除失败的孤儿文件留给存储维护任务清理。
        }
    }

    private record DecodedImage(BufferedImage image, String format, String mimeType) {}
}
