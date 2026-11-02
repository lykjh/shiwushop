package com.shiwushop.system.account;

import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

/** 需要专用 Nginx 测试代理及 native 转发头后端；Nginx 测试响应头只在隔离配置出现。 */
class ProxyClientIpSystemIT {
    @Test void nginxOverwritesForgedForwardedForAndTomcatUsesTheActualClientAddress() throws Exception {
        String base=SystemEnvironment.base();
        var connection=new LettuceConnectionFactory("127.0.0.1",16379);connection.afterPropertiesSet();
        var redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();Set<String> keys=new HashSet<>();
        try(var client=new HttpAccountClient(base)) {
            client.csrf();
            for(String spoof:List.of("198.51.100.20","203.0.113.21")) {
                var response=client.request("POST","/users/login",Map.of("username","absent"+System.nanoTime(),"password","Wrong123!"),true,Map.of("X-Forwarded-For",spoof));
                assertEquals(401,response.statusCode(),response.body());
                String real=response.headers().firstValue("X-Test-Client-Ip").orElseThrow();
                assertNotEquals(spoof,real);
                String realKey=key(real),forgedKey=key(spoof);keys.add(realKey);keys.add(forgedKey);
                assertTrue(redis.hasKey(realKey),"Tomcat 未使用 Nginx 记录的客户端地址");assertFalse(redis.hasKey(forgedKey),"客户端可伪造限流身份");
            }
        } finally {if(!keys.isEmpty())redis.delete(keys);connection.destroy();}
    }
    private String key(String ip) throws Exception {
        return "shiwushop:proxy-test:login:bucket:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8)));
    }
}
