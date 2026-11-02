package com.shiwushop.common.media.service.impl;

import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.media.entity.MediaAsset;
import com.shiwushop.common.media.mapper.MediaAssetMapper;
import com.shiwushop.common.media.service.MediaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 仅负责图片上传、解码和持久化；读取授权由 MediaReadServiceImpl 负责。 */
@Service
@Slf4j
public class MediaServiceImpl implements MediaService {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;
    private final MediaAssetMapper assets;
    private final AccountSecurity accounts;
    private final Semaphore uploadSlots;
    private final Semaphore admittedUploads;
    private final long waitMillis;

    /** 分别限制重处理并发和等待人数，防止批量请求立即被拒绝或无限占用请求线程。 */
    public MediaServiceImpl(MediaAssetMapper assets, AccountSecurity accounts,
            @Value("${shop.media.upload-max-concurrent:2}") int concurrent,
            @Value("${shop.media.upload-max-waiting:16}") int waiting,
            @Value("${shop.media.upload-wait-ms:10000}") long waitMillis) {
        if (concurrent < 1 || waiting < 0 || waitMillis < 1)
            throw new IllegalArgumentException("图片并发和等待时间必须为正，等待人数不得为负");
        this.assets = assets;
        this.accounts = accounts;
        this.uploadSlots = new Semaphore(concurrent, true);
        this.admittedUploads = new Semaphore(Math.addExact(concurrent, waiting), true);
        this.waitMillis = waitMillis;
    }

    @Value("${shop.media.root:./local-data/media}")
    private String storageRoot;

    /**
     * 校验用户与文件大小，暂存原图并按实际格式解码、重编码后落盘。
     * 成功写入图片记录才返回 ID；失败清理临时文件和未入库的目标文件。
     */
    @Override
    public Long upload(Long userId, MultipartFile file) {
        accounts.checkActive(userId);
        if (file == null || file.isEmpty()) throw new BusinessException(400, "图片不能为空");
        if (file.getSize() > MAX_BYTES) throw new BusinessException(413, "图片不能超过 10 MiB");
        acquireUploadSlot();
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
            BusinessEventLog.committed("media", "image_uploaded", "media", asset.getId());
            return asset.getId();
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
            admittedUploads.release();
        }
    }

    /** 有限人数按公平顺序等待处理许可；超时和中断都归还接收名额，尚未开始图片解码。 */
    private void acquireUploadSlot() {
        if (!admittedUploads.tryAcquire()) throw busy();
        boolean acquired = false;
        try {
            acquired = uploadSlots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "图片上传等待已中断，请重试", error);
        } finally {
            if (!acquired) admittedUploads.release();
        }
        if (!acquired) throw busy();
    }

    /** 队列已满或等待超时时提供明确重试时间，调用方无需猜测何时重试。 */
    private BusinessException busy() {
        return new BusinessException(429, "图片处理繁忙，请稍后重试",
                Map.of("retryAfterSeconds", 1), null);
    }

    /** 读取真实格式与尺寸，仅接收 JPEG/PNG 且像素不超过上限。 */
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

    /** 创建上传原始字节的 SHA-256 摘要器，用于图片记录。 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 尽力删除上传过程的临时文件；清理失败交由残留文件任务处理。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            // 清理失败不覆盖原始上传异常；残留文件由维护任务再次处理。
            log.warn("图片临时文件清理失败 file={} error={}", path.getFileName(), error.getClass().getSimpleName());
        }
    }

    private record DecodedImage(BufferedImage image, String format, String mimeType) {}
}
