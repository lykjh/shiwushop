package com.shiwushop.product;

import com.shiwushop.dto.product.PublicProductQuery;
import com.shiwushop.vo.product.PublicProductPageVO;
import com.shiwushop.vo.product.PublicProductVO;
import com.shiwushop.catalog.cache.PublicProductCache;
import com.shiwushop.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "shop.public-cache.ttl-seconds=1")
class PublicProductCacheTests {
    @Autowired private PublicProductCache cache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper json;
    @MockitoBean private ProductService products;

    @Test
    void reusesPublicPageAndRequeriesAfterBoundedTtl() throws Exception {
        PublicProductQuery query = new PublicProductQuery(null, null, null, null, null, 20, null);
        PublicProductPageVO first = page(1L);
        PublicProductPageVO changed = page(2L);
        String key = "shiwushop:public:products:v3:{home}:page";
        try {
            redis.delete(key);
            when(products.publicList(query)).thenReturn(first, changed);
            assertEquals(first, cache.list(query));
            assertEquals(first, cache.list(query));
            verify(products, times(1)).publicList(query);
            Thread.sleep(1200);
            assertEquals(changed, cache.list(query));
            verify(products, times(2)).publicList(query);
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void skipsRedisForFilteredAndCursorRequests() {
        ProductService source = mock(ProductService.class);
        StringRedisTemplate unused = mock(StringRedisTemplate.class);
        PublicProductCache isolated = new PublicProductCache(source, unused, json);
        PublicProductQuery filtered = new PublicProductQuery(1L, null, null, null, null, 20, null);
        PublicProductQuery cursor = new PublicProductQuery(null, null, null, null, null, 20, 100L);
        when(source.publicList(filtered)).thenReturn(page(1L));
        when(source.publicList(cursor)).thenReturn(page(2L));

        assertEquals(page(1L), isolated.list(filtered));
        assertEquals(page(2L), isolated.list(cursor));
        verifyNoInteractions(unused);
    }

    @Test
    void redisFailureFallsBackToDatabaseResult() {
        ProductService source = mock(ProductService.class);
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        PublicProductCache isolated = new PublicProductCache(source, unavailable, json);
        ReflectionTestUtils.setField(isolated, "ttlSeconds", 1L);
        PublicProductQuery query = new PublicProductQuery(null, null, null, null, null, 20, null);
        PublicProductPageVO expected = page(3L);
        when(source.publicList(query)).thenReturn(expected);
        when(unavailable.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals(expected, isolated.list(query));
        verify(source).publicList(query);
    }

    @Test
    void redisFailureLimitsConcurrentDatabaseFallbacks() throws Exception {
        ProductService source = mock(ProductService.class);
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        PublicProductCache isolated = new PublicProductCache(source, unavailable, json);
        ReflectionTestUtils.setField(isolated, "waitMillis", 1000L);
        PublicProductQuery query = new PublicProductQuery(null, null, null, null, null, 20, null);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(unavailable.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        when(source.publicList(query)).thenAnswer(call -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
                return page(3L);
            } finally {
                active.decrementAndGet();
            }
        });
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var calls = java.util.stream.IntStream.range(0, 3).mapToObj(index -> workers.submit(() -> {
                start.await();
                return isolated.list(query);
            })).toList();
            start.countDown();
            for (var call : calls) assertEquals(page(3L), call.get(3, TimeUnit.SECONDS));
        }
        assertEquals(1, peak.get());
    }

    @Test
    void expiredLeaseCannotOverwriteNewerCache() throws Exception {
        ProductService source = mock(ProductService.class);
        PublicProductCache isolated = new PublicProductCache(source, redis, json);
        ReflectionTestUtils.setField(isolated, "ttlSeconds", 5L);
        ReflectionTestUtils.setField(isolated, "lockSeconds", 30L);
        ReflectionTestUtils.setField(isolated, "waitMillis", 1000L);
        PublicProductQuery query = new PublicProductQuery(null, null, null, null, null, 20, null);
        String key = "shiwushop:public:products:v3:{home}:page";
        String lockKey = "shiwushop:public:products:v3:{home}:lock";
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        when(source.publicList(query)).thenAnswer(call -> {
            if (queries.incrementAndGet() == 1) {
                firstStarted.countDown();
                assertTrue(finishFirst.await(3, TimeUnit.SECONDS));
                return page(1L);
            }
            return page(2L);
        });
        redis.delete(List.of(key, lockKey));
        try (var worker = Executors.newSingleThreadExecutor()) {
            try {
                var oldRequest = worker.submit(() -> isolated.list(query));
                assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
                redis.delete(lockKey); // 模拟旧租约过期后被新请求取得。
                assertEquals(page(2L), isolated.list(query));
                finishFirst.countDown();
                assertEquals(page(1L), oldRequest.get(3, TimeUnit.SECONDS));
                assertEquals(page(2L), isolated.list(query));
            } finally {
                finishFirst.countDown();
                redis.delete(List.of(key, lockKey));
            }
        }
        assertEquals(2, queries.get());
    }

    private static PublicProductPageVO page(long id) {
        PublicProductVO item = new PublicProductVO(id, "测试商品", null, null,
                new PublicProductVO.Shop(1L, "测试店铺"), BigDecimal.TEN, 0L, false);
        return new PublicProductPageVO(List.of(item), 20, false, null);
    }
}
