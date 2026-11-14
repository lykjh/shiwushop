package com.shiwushop.common.auth;

import com.shiwushop.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

@Configuration
public class WebSecurityConfig {
    private final ObjectMapper objectMapper;

    public WebSecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** CSRF 绑定匿名浏览器和已经过校验的 JWT 登录记录。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BrowserFlow flow) throws Exception {
        var masked = new XorCsrfTokenRequestAttributeHandler();
        CsrfTokenRequestHandler csrfHeaders = new CsrfTokenRequestHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
                masked.handle(request, response, token);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                return request.getHeader(token.getHeaderName()) == null
                        ? null : masked.resolveCsrfTokenValue(request, token);
            }
        };
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())//所有请求全部放行
                .formLogin(AbstractHttpConfigurer::disable)//关闭默认登陆页
                .httpBasic(AbstractHttpConfigurer::disable)//关闭浏览器认证框
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(new SignedCsrfTokenRepository(flow))
                        .csrfTokenRequestHandler(csrfHeaders).ignoringRequestMatchers("/payments/alipay/notify"))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(Result.error(403, "请求被拒绝")));
                }))
                .build();
    }
}
