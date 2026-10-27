package com.shiwushop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@MapperScan("com.shiwushop.mapper")
@SpringBootApplication
@EnableScheduling
public class ShiwushopApplication {

    @Bean
    Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ShiwushopApplication.class, args);
    }

}
