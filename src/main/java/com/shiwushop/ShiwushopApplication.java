package com.shiwushop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@MapperScan({"com.shiwushop.account.mapper", "com.shiwushop.catalog.mapper", "com.shiwushop.commerce.mapper", "com.shiwushop.common.media.mapper", "com.shiwushop.common.outbox"})
@SpringBootApplication
@EnableScheduling
public class ShiwushopApplication {

    /** 为订单、支付和物流提供统一的上海时区业务时钟。 */
    @Bean
    Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ShiwushopApplication.class, args);
    }

}
