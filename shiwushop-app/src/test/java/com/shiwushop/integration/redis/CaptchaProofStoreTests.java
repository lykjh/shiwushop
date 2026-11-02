package com.shiwushop.integration.redis;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptchaProofStoreTests {
    static LettuceConnectionFactory connection;static StringRedisTemplate redis;
    CaptchaProofStore store;RateLimiter limiter;final List<String> keys=new ArrayList<>();
    @BeforeAll static void connect(){connection=new LettuceConnectionFactory("127.0.0.1",16379);connection.afterPropertiesSet();redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();}
    @AfterAll static void disconnect(){connection.destroy();}
    @BeforeEach void prepare(){limiter=new RateLimiter(redis);store=new CaptchaProofStore(redis,limiter);}
    @AfterEach void cleanup(){if(!keys.isEmpty())redis.delete(keys);}
    String issue(){String id="captcha"+System.nanoTime();keys.add("shiwushop:captcha:used:"+limiter.digest(id));String proof=store.issue(id,"owner");keys.add("shiwushop:captcha:login:proof:"+limiter.digest(proof));return proof;}
    @Test void sameCaptchaCannotIssueTwoProofs(){String id="captcha"+System.nanoTime();keys.add("shiwushop:captcha:used:"+limiter.digest(id));String proof=store.issue(id,"owner");keys.add("shiwushop:captcha:login:proof:"+limiter.digest(proof));assertThrows(BusinessException.class,()->store.issue(id,"owner"));}
    @Test void otherSessionIsRejectedAndOneTimeProofRemainsConsumed(){String proof=issue();assertThrows(BusinessException.class,()->store.consume(proof,"other"));assertThrows(BusinessException.class,()->store.consume(proof,"owner"));}
    @Test void simultaneousConsumptionOnlyPermitsOneRequest() throws Exception {
        String proof=issue();try(var pool=Executors.newFixedThreadPool(8)){
            CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<16;i++)results.add(pool.submit(()->{start.await();try{store.consume(proof,"owner");return true;}catch(BusinessException rejected){assertEquals(400,rejected.getCode());return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))winners++;assertEquals(1,winners);
        }
    }
    @Test void expiredProofIsRejected() throws Exception {
        String proof=issue();String key=keys.getLast();assertTrue(redis.getExpire(key,TimeUnit.SECONDS)<=120);
        redis.expire(key,Duration.ofMillis(1));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Boolean.TRUE.equals(redis.hasKey(key))&&System.nanoTime()<deadline)Thread.sleep(5);
        assertFalse(redis.hasKey(key));assertThrows(BusinessException.class,()->store.consume(proof,"owner"));
    }
}
