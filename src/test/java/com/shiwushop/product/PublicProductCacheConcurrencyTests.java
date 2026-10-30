package com.shiwushop.product;

import com.shiwushop.catalog.cache.PublicProductCache;
import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.service.PublicProductService;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicProductCacheConcurrencyTests {
    @Test
    void concurrentHomePageMissesLoadTheDatabaseOnce() throws Exception {
        PublicProductService products = mock(PublicProductService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AtomicReference<String> cacheValue = new AtomicReference<>();
        AtomicReference<String> lockToken = new AtomicReference<>();
        when(values.get(anyString())).thenAnswer(call -> cacheValue.get());
        when(values.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(call -> lockToken.compareAndSet(null, call.getArgument(1)));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    cacheValue.set(call.getArgument(3));
                    return 1L;
                });
        doAnswer(call -> {
            cacheValue.set(call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));

        PublicProductQuery query = new PublicProductQuery(null, null, null, null, null, 20, null);
        PublicProductPageVO page = new PublicProductPageVO(List.of(), 20, false, null);
        AtomicInteger databaseQueries = new AtomicInteger();
        when(products.publicList(query)).thenAnswer(call -> {
            databaseQueries.incrementAndGet();
            Thread.sleep(300);
            return page;
        });
        PublicProductCache cache = new PublicProductCache(products, redis, new ObjectMapper());
        ReflectionTestUtils.setField(cache, "ttlSeconds", 5L);
        ReflectionTestUtils.setField(cache, "lockSeconds", 30L);
        ReflectionTestUtils.setField(cache, "waitMillis", 1000L);

        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(index -> workers.submit(() -> {
                ready.countDown();
                start.await();
                return cache.list(query);
            })).toList();
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers not ready");
            start.countDown();
            for (var call : calls) call.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, databaseQueries.get(), "同一热点缓存失效不应触发多次数据库查询");
    }
}
