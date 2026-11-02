package com.shiwushop.unit.media;

import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.entity.MediaAsset;
import com.shiwushop.common.media.mapper.MediaAssetMapper;
import com.shiwushop.common.media.service.impl.MediaServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadUnitTests {
    @TempDir Path root;

    @Test
    void streamsOriginalWithoutMaterializingMultipartBytes() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] original = encoded.toByteArray();
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) original.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(original));
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        doAnswer(invocation -> {
            ((MediaAsset) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(assets).insert(any(MediaAsset.class));
        MediaServiceImpl service = new MediaServiceImpl(assets, mock(AccountAccess.class), 2, 16, 10000);
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());

        assertEquals(7L, service.upload(1L, file));

        verify(file, never()).getBytes();
        var asset = org.mockito.ArgumentCaptor.forClass(MediaAsset.class);
        verify(assets).insert(asset.capture());
        assertEquals(original.length, asset.getValue().getByteSize());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(original), asset.getValue().getSha256());
        try (var files = Files.list(root)) {
            assertEquals(1, files.count()); // 原图暂存文件已清理，仅保留最终图片。
        }
    }

    @Test
    void rejectsActualBytesBeyondLimitEvenWhenReportedSizeIsSmall() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]));
        MediaAssetMapper assets = mock(MediaAssetMapper.class);
        MediaServiceImpl service = new MediaServiceImpl(assets, mock(AccountAccess.class), 2, 16, 10000);
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());

        assertEquals(413, assertThrows(BusinessException.class, () -> service.upload(1L, file)).getCode());
        verify(assets, never()).insert(any(MediaAsset.class));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
    @Test void waitsForBusyProcessorAndRejectsOnlyWhenWaitingRoomIsFull() throws Exception {
        var service = service(1, 1, 3000);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = heldImage(started, release); var normal = image();
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> service.upload(1L, blocked));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                var thread = new AtomicReference<Thread>();
                var second = workers.submit(() -> {thread.set(Thread.currentThread()); return service.upload(1L, normal);});
                awaitWaiting(thread);
                var rejected = assertThrows(BusinessException.class, () -> service.upload(1L, normal));
                assertEquals(429, rejected.getCode());
                assertEquals(1, ((Map<?,?>) rejected.getData()).get("retryAfterSeconds"));
                release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS) > 0);
                assertTrue(second.get(5, TimeUnit.SECONDS) > 0);
                assertTrue(service.upload(1L, normal) > 0);
            } finally {release.countDown();}
        }
    }

    @Test void timeoutReturnsItsWaitingPlaceForTheNextRequest() throws Exception {
        var service = service(1, 1, 250);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = heldImage(started, release); var normal = image();
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> service.upload(1L, blocked));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertEquals(429, assertThrows(BusinessException.class, () -> service.upload(1L, normal)).getCode());
                var thread = new AtomicReference<Thread>();
                var next = workers.submit(() -> {thread.set(Thread.currentThread()); return service.upload(1L, normal);});
                awaitWaiting(thread); release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS) > 0); assertTrue(next.get(5, TimeUnit.SECONDS) > 0);
            } finally {release.countDown();}
        }
    }

    @Test void interruptedWaitPreservesInterruptAndReturnsItsWaitingPlace() throws Exception {
        var service = service(1, 1, 3000);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocked = heldImage(started, release); var normal = image();
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> service.upload(1L, blocked));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                var thread = new AtomicReference<Thread>();
                var interrupted = workers.submit(() -> {
                    thread.set(Thread.currentThread());
                    var error = assertThrows(BusinessException.class, () -> service.upload(1L, normal));
                    assertEquals(503, error.getCode()); return Thread.currentThread().isInterrupted();
                });
                awaitWaiting(thread); thread.get().interrupt();
                assertTrue(interrupted.get(5, TimeUnit.SECONDS));
                thread.set(null);
                var next = workers.submit(() -> {thread.set(Thread.currentThread()); return service.upload(1L, normal);});
                awaitWaiting(thread); release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS) > 0); assertTrue(next.get(5, TimeUnit.SECONDS) > 0);
            } finally {release.countDown();}
        }
    }

    @Test void failedStorageReadReleasesProcessingAndAdmissionPlaces() throws Exception {
        var service = service(1, 0, 1000);
        var broken = mock(MultipartFile.class);
        when(broken.getSize()).thenReturn(1L);
        when(broken.getInputStream()).thenThrow(new IOException("disk failure"));
        assertEquals(503, assertThrows(BusinessException.class, () -> service.upload(1L, broken)).getCode());
        assertTrue(service.upload(1L, image()) > 0);
    }

    @ParameterizedTest @CsvSource({"0,1,10", "1,-1,10", "1,1,0"})
    void invalidCapacityFailsBeforeAcceptingRequests(int concurrent, int waiting, long waitMillis) {
        assertThrows(IllegalArgumentException.class, () -> service(concurrent, waiting, waitMillis));
    }

    private MediaServiceImpl service(int concurrent, int waiting, long waitMillis) {
        var assets = mock(MediaAssetMapper.class); var sequence = new AtomicLong();
        doAnswer(call -> {((MediaAsset) call.getArgument(0)).setId(sequence.incrementAndGet()); return 1;})
                .when(assets).insert(any(MediaAsset.class));
        var service = new MediaServiceImpl(assets, mock(AccountAccess.class), concurrent, waiting, waitMillis);
        ReflectionTestUtils.setField(service, "storageRoot", root.toString()); return service;
    }

    private MultipartFile image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("file", "毛巾.png", "image/png", output.toByteArray());
    }

    /** 在原图流读取边界挂起处理者，稳定制造“处理占满”的时序。 */
    private MultipartFile heldImage(CountDownLatch started, CountDownLatch release) throws Exception {
        byte[] bytes = image().getBytes(); var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getInputStream()).thenAnswer(call -> new InputStream() {
            final InputStream source = new ByteArrayInputStream(bytes);
            @Override public int read() throws IOException {return source.read();}
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                started.countDown();
                try {if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out");}
                catch (InterruptedException error) {Thread.currentThread().interrupt(); throw new IOException(error);}
                return source.read(buffer, offset, length);
            }
        });
        return file;
    }

    /** 确认调用已进入有超时等待再发送超额请求，避免依赖随机线程调度。 */
    private void awaitWaiting(AtomicReference<Thread> thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (thread.get() != null && thread.get().getState() == Thread.State.TIMED_WAITING) return;
            Thread.sleep(1);
        }
        throw new AssertionError("request never entered bounded wait");
    }
}
