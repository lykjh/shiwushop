package com.shiwushop.user.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 只将 JWT 和共享登录记录均已验证的身份交给控制器。 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
@RequiredArgsConstructor
public class SessionValidationFilter extends OncePerRequestFilter {
    private final JwtLogin login;
    private final BrowserFlow browserFlow;
    /** 将业务对象与 JSON 相互转换。 */
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            browserFlow.identify(request);
            var identity = login.authenticate(request);
            if (identity != null)
                SessionUser.identify(request, identity);
        } catch (BusinessException e) {
            response.setStatus(e.getCode());
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.error(e.getCode(), e.getMessage())));
            return;
        }
        chain.doFilter(request, response);
    }
}
