package com.shiwushop.system.account;

import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 显式故障复现：只停止专用 26379 容器，finally 必须恢复并验证能够重新登录。 */
class RedisDependencyFailureSystemIT {
    @Test void outageReturns503AndAuthenticationRecoversAfterRedisRestarts() throws Exception {
        String base=SystemEnvironment.base();assertEquals("http://127.0.0.1:18085",base);
        String container=System.getProperty("system.redis-container");assertEquals("shiwushop-redis-fault-test-20261101",container);
        try(var accounts=new TestAccounts(SystemEnvironment.database())){
            var buyer=accounts.create("USER","Shop123!");int failedStatus=-1;
            try(var client=CommerceFixture.login(base,buyer)){
                assertEquals(200,client.get("/users/me").statusCode());
                try {
                    docker("stop",container);
                    var response=client.get("/users/me");failedStatus=response.statusCode();
                    System.out.println("REDIS_OUTAGE observed_http="+failedStatus);
                    assertEquals(503, new tools.jackson.databind.ObjectMapper().readTree(response.body()).path("code").asInt());
                    assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
                    assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
                } finally {
                    docker("start",container);
                    boolean recovered=false;java.io.IOException lastFailure=null;int lastStatus=503;
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                    while(!recovered&&System.nanoTime()<deadline){
                        try(var fresh=new HttpAccountClient(base)) {
                            var csrf=fresh.get("/users/csrf");lastStatus=csrf.statusCode();
                            if(lastStatus==503){Thread.sleep(200);continue;}
                            assertEquals(200,lastStatus,csrf.body());
                            String token=new tools.jackson.databind.ObjectMapper().readTree(csrf.body()).path("data").asString();
                            var login=fresh.request("POST","/users/login",Map.of("username",buyer.username(),"password",buyer.password()),
                                    false,Map.of("X-CSRF-TOKEN",token));
                            lastStatus=login.statusCode();
                            if(lastStatus==503){Thread.sleep(200);continue;}
                            assertEquals(200,lastStatus,login.body());
                            lastStatus=fresh.get("/users/me").statusCode();
                            recovered=lastStatus==200;
                        }
                        catch(java.io.IOException retry){lastFailure=retry;Thread.sleep(200);}
                    }
                    assertTrue(recovered,"Redis 重启后无法重新登录；HTTP="+lastStatus+"，最近连接故障="+lastFailure);
                }
            }
            assertEquals(503,failedStatus,"Redis Session 故障必须按约定返回 503");
        }
    }
    private void docker(String operation,String container) throws Exception {
        Process process=new ProcessBuilder("docker",operation,container).redirectErrorStream(true).start();
        assertTrue(process.waitFor(25,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
    }
}
