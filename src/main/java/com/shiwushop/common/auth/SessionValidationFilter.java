package com.shiwushop.common.auth;

import com.shiwushop.account.auth.AccountAuthCache;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;


import com.shiwushop.account.entity.status.AccountStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** 每次使用会话前核对共享的账号状态和版本；缓存暂不可用时拒绝请求但保留会话。 */

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class SessionValidationFilter extends OncePerRequestFilter {
    private final AccountAuthCache authCache;
    private final ObjectMapper objectMapper;

    /** 每个携带登录 Session 的请求都校验账号状态和鉴权版本；异常时保留 Session。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object principal = session == null ? null : session.getAttribute("userId");
        if (principal instanceof Long userId) {
            AccountAuthCache.State state;
            try {
                state = authCache.current(userId);
            } catch (BusinessException e) {
                response.setStatus(e.getCode());       // Redis 故障或账号变更中：503
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.error(e.getCode(), e.getMessage())));
                return;                                 // 不注销会话，也不放行
            }

            int sessionVersion = session.getAttribute("authVersion") instanceof Integer v ? v : 0;
            if (state == null || state.status() != AccountStatus.ACTIVE
                    || sessionVersion != state.version()) {
                session.invalidate();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.error(401, "请重新登录")));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
