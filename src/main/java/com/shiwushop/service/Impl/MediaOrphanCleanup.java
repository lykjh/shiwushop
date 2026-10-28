package com.shiwushop.service.impl;

import com.shiwushop.mapper.media.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaOrphanCleanup {
    private static final Pattern STORED_IMAGE = Pattern.compile(
            "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\.(png|jpg)");
    private final MediaAssetMapper assets;

    @Value("${shop.media.root:./local-data/media}")
    private String storageRoot;
    @Value("${shop.media.orphan-retention-hours:24}")
    private long retentionHours;

    @Scheduled(initialDelayString = "${shop.media.cleanup-delay-ms:86400000}",
            fixedDelayString = "${shop.media.cleanup-delay-ms:86400000}")
    public void clean() {
        if (retentionHours < 24) {
            log.warn("图片残留文件保留时间必须至少 24 小时");
            return;
        }
        Path root = Path.of(storageRoot).toAbsolutePath().normalize();
        if (!Files.exists(root)) return;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("图片目录不可扫描");
            return;
        }
        Instant cutoff = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean storedImage = STORED_IMAGE.matcher(name).matches();
                boolean uploadTemp = (name.startsWith("source-") || name.startsWith("upload-"))
                        && name.endsWith(".tmp");
                if (!storedImage && !uploadTemp) continue;
                try {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff))
                        continue;
                    // 先核对持久记录；数据库不可用时停止清理，避免误删已入库图片。
                    if (storedImage && assets.existsByStorageKey(name)) continue;
                    if (Files.deleteIfExists(file)) deleted++;
                } catch (IOException e) {
                    log.warn("图片残留文件清理失败 name={} type={}", name, e.getClass().getSimpleName());
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("图片目录扫描失败 type={}", e.getClass().getSimpleName());
        }
        if (deleted > 0) log.info("图片残留文件清理完成 deleted={}", deleted);
    }
}
