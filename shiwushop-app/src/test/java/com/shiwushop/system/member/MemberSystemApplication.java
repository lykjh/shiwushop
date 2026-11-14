package com.shiwushop.system.member;

import com.shiwushop.ShiwushopApplication;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.PaymentHttpFixture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;

import static org.mockito.Mockito.mock;

/** 仅test classpath可用：外部渠道替身和固定业务时钟，不向生产应用加入测试端点。 */
public final class MemberSystemApplication {
    public static void main(String[] args) {
        if (!Boolean.getBoolean("member.system.enabled"))
            throw new IllegalStateException("独立会员测试须显式启用");
        new SpringApplicationBuilder(ShiwushopApplication.class, SystemConfiguration.class).run(args);
    }

    @TestConfiguration
    static class SystemConfiguration {
        @Bean
        @Primary
        Clock memberSystemClock(@Value("${member.system.now}") String now) {
            return Clock.fixed(LocalDateTime.parse(now).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                    ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        @Primary
        SdkAlipayGateway memberSystemGateway() {
            var gateway = mock(SdkAlipayGateway.class);
            PaymentHttpFixture.configure(gateway);
            return gateway;
        }
    }
}
