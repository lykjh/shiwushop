package com.shiwushop.support;

import com.shiwushop.common.auth.WebSecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/** MVC 切片显式使用实际 CSRF 链，不引入应用启动类上的 MapperScan。 */
@TestConfiguration
@EnableWebSecurity
@Import(WebSecurityConfig.class)
public class ControllerSliceSecurity {}
