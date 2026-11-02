package com.shiwushop.integration.redis;

import com.shiwushop.user.auth.PhoneRegistrationTicketStore;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis 组件集成：票据绑定、原子消费和 TTL，不依赖应用容器。 */
class PhoneRegistrationTicketStoreTests {
    static LettuceConnectionFactory connection;static StringRedisTemplate redis;
    PhoneRegistrationTicketStore store;final List<String> keys=new ArrayList<>();
    @BeforeAll static void connect(){connection=new LettuceConnectionFactory("127.0.0.1",16379);connection.afterPropertiesSet();redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();}
    @AfterAll static void disconnect(){connection.destroy();}
    @BeforeEach void prepare(){store=new PhoneRegistrationTicketStore(redis);}
    @AfterEach void cleanup(){if(!keys.isEmpty())redis.delete(keys);}
    String ticket() throws Exception {
        String ticket=store.issue("phone-digest","owner-session");
        keys.add("shiwushop:sms:registration:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8))));return ticket;
    }
    @Test void wrongPhoneOrSessionDoesNotConsumeTheOwnersTicket() throws Exception {
        String ticket=ticket();
        assertEquals(401,assertThrows(BusinessException.class,()->store.consume(ticket,"wrong-phone","owner-session")).getCode());
        assertEquals(401,assertThrows(BusinessException.class,()->store.consume(ticket,"phone-digest","stranger")).getCode());
        assertDoesNotThrow(()->store.consume(ticket,"phone-digest","owner-session"));
        assertThrows(BusinessException.class,()->store.consume(ticket,"phone-digest","owner-session"));
    }
    @Test void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        String ticket=ticket();
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<16;i++)results.add(pool.submit(()->{start.await();try{store.consume(ticket,"phone-digest","owner-session");return true;}catch(BusinessException rejected){assertEquals(401,rejected.getCode());return false;}}));
            start.countDown();int winners=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))winners++;assertEquals(1,winners);
        }
    }
    @Test void expiredTicketIsRejectedAndStoresOnlyDigests() throws Exception {
        String ticket=ticket();String key=keys.getFirst();
        assertTrue(redis.getExpire(key,TimeUnit.SECONDS)>0);assertTrue(redis.getExpire(key,TimeUnit.SECONDS)<=300);
        assertFalse(Objects.requireNonNull(redis.opsForValue().get(key)).contains("owner-session"));
        redis.expire(key,Duration.ofMillis(1));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(Boolean.TRUE.equals(redis.hasKey(key))&&System.nanoTime()<deadline)Thread.sleep(5);
        assertFalse(redis.hasKey(key));assertThrows(BusinessException.class,()->store.consume(ticket,"phone-digest","owner-session"));
    }
}
