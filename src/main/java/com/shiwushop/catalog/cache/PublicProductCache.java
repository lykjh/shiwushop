package com.shiwushop.catalog.cache;

import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;
import com.shiwushop.catalog.service.PublicProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublicProductCache {
    // Redis Cluster 的多键脚本要求两个键落在同一槽。
    private static final String HOME_KEY = "shiwushop:public:products:v3:{home}:page";
    private static final String LOCK_KEY = "shiwushop:public:products:v3:{home}:lock";
    // 只释放自己持有的租约，避免过期后的旧请求删掉新请求的租约。
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end; return 0",
            Long.class);
    // 回填也核对租约，防止慢查询在租约过期后覆盖较新的首页数据。
    private static final DefaultRedisScript<Long> FILL_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end; " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1",
            Long.class);
    private final PublicProductService products;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Semaphore fallbackSlot = new Semaphore(1);

    @Value("${shop.public-cache.ttl-seconds:5}")
    private long ttlSeconds;
    @Value("${shop.public-cache.lock-seconds:30}")
    private long lockSeconds;
    @Value("${shop.public-cache.wait-millis:1000}")
    private long waitMillis;

    /**
     * 只缓存无筛选的首页首批；其他筛选和深页直接查公开商品服务。
     * 缓存未命中时用 Redis 租约限制同键回源，等待者短暂重读；超时或缓存故障进入有界兜底。
     */
    public PublicProductPageVO list(PublicProductQuery query) {
        if (query.afterId() != null || query.size() != 20 || query.categoryId() != null
                || query.brandId() != null || query.shopId() != null
                || query.minPrice() != null || query.maxPrice() != null) {
            return products.publicList(query);
        }
        PublicProductPageVO cached;
        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            cached = readHome();
            if (cached != null) return cached;
            acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token,
                    Duration.ofSeconds(lockSeconds)));
        } catch (Exception e) {
            log.warn("公开商品缓存读取失败：{}", e.getClass().getSimpleName());
            return fallback(query);
        }
        if (acquired) {
            try {
                try {
                    cached = readHome();
                } catch (Exception e) {
                    log.warn("公开商品缓存读取失败：{}", e.getClass().getSimpleName());
                    cached = null;
                }
                if (cached != null) return cached;
                PublicProductPageVO result = products.publicList(query);
                try {
                    redis.execute(FILL_IF_OWNER, List.of(HOME_KEY, LOCK_KEY), token,
                            json.writeValueAsString(result), Long.toString(TimeUnit.SECONDS.toMillis(ttlSeconds)));
                } catch (Exception e) {
                    log.warn("公开商品缓存写入失败：{}", e.getClass().getSimpleName());
                }
                return result;
            } finally {
                try {
                    redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), token);
                } catch (Exception e) {
                    log.warn("公开商品回源租约释放失败：{}", e.getClass().getSimpleName());
                }
            }
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (System.nanoTime() < deadline) {
                Thread.sleep(ThreadLocalRandom.current().nextLong(20, 51));
                cached = readHome();
                if (cached != null) return cached;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "商品列表暂不可用", e);
        } catch (Exception e) {
            log.warn("公开商品缓存读取失败：{}", e.getClass().getSimpleName());
            return fallback(query);
        }
        return fallback(query);
    }

    /**
     * Redis 故障或等待回填超时后的兜底入口。每个应用实例最多一个回源查询；
     * 未在等待期限内取得许可时再查一次缓存，仍未命中则返回 503。
     */
    private PublicProductPageVO fallback(PublicProductQuery query) {
        try {
            if (!fallbackSlot.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                try {
                    PublicProductPageVO cached = readHome();
                    if (cached != null) return cached;
                } catch (Exception e) {
                    log.warn("公开商品缓存读取失败：{}", e.getClass().getSimpleName());
                }
                throw new BusinessException(503, "商品列表暂不可用");
            }
            try {
                PublicProductPageVO cached;
                try {
                    cached = readHome();
                } catch (Exception e) {
                    log.warn("公开商品缓存读取失败：{}", e.getClass().getSimpleName());
                    cached = null;
                }
                return cached != null ? cached : products.publicList(query);
            } finally {
                fallbackSlot.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "商品列表暂不可用", e);
        }
    }

    /** 读取并反序列化首页缓存；键不存在时返回空，由调用方决定回源或等待。 */
    private PublicProductPageVO readHome() {
        String value = redis.opsForValue().get(HOME_KEY);
        return value == null ? null : json.readValue(value, PublicProductPageVO.class);
    }
}
