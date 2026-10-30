package com.shiwushop.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpRequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestLogFilter.class);

    /** 为每次 HTTP 请求生成追踪 ID，并记录路由模板、状态码和耗时。 */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 记录匹配的路由模板，避免把 URL 中的交易号或用户输入写进日志。
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
            String module = handler instanceof HandlerMethod method
                    ? moduleOf(method.getBeanType().getPackageName()) : "unmapped";
            log.info("http method={} route={} status={} durationMs={} requestId={} module={}",
                    request.getMethod(), route == null ? "[unmapped]" : route,
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000, requestId, module);
            MDC.remove("requestId");
        }
    }

    private static String moduleOf(String packageName) {
        if (packageName.startsWith("com.shiwushop.account.")) return "account";
        if (packageName.startsWith("com.shiwushop.catalog.")) return "catalog";
        if (packageName.startsWith("com.shiwushop.commerce.")) return "commerce";
        if (packageName.startsWith("com.shiwushop.common.media.")) return "media";
        return "common";
    }
}
