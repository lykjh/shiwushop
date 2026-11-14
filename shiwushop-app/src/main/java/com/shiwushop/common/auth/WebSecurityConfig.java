package com.shiwushop.common.auth;

import com.shiwushop.common.Result;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WebSecurityConfig {
    private final ObjectMapper objectMapper;

    public WebSecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 开启框架 CSRF，支付平台回调例外；接口鉴权由业务 Session 校验执行。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())//所有请求全部放行
                .formLogin(AbstractHttpConfigurer::disable)//关闭默认登陆页
                .httpBasic(AbstractHttpConfigurer::disable)//关闭浏览器认证框
                .csrf(csrf -> csrf.ignoringRequestMatchers("/payments/alipay/notify"))//CSRF 保护默认开启，唯独放行支付宝异步通知
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(Result.error(403, "请求被拒绝")));
                }))
                .build();
    }
}
