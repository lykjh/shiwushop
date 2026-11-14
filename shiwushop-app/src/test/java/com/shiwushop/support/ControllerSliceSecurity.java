package com.shiwushop.support;

import com.shiwushop.common.auth.WebSecurityConfig;
import com.shiwushop.common.auth.BrowserFlow;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import java.time.Clock;
import java.util.Base64;

/** MVC 切片显式使用实际 CSRF 链，不引入应用启动类上的 MapperScan。 */
@TestConfiguration
@EnableWebSecurity
@Import(WebSecurityConfig.class)
public class ControllerSliceSecurity {
    @Bean
    BrowserFlow browserFlow() {
        return new BrowserFlow(Clock.systemUTC(), Base64.getEncoder().encodeToString(new byte[32]), false);
    }
}
