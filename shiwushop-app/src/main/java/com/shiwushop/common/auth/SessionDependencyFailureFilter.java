package com.shiwushop.common.auth;

import com.shiwushop.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在 Spring Session 外层处理依赖故障，覆盖会话读取及请求收尾保存。 */
@Slf4j
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
@RequiredArgsConstructor
public class SessionDependencyFailureFilter extends OncePerRequestFilter {
    private final ObjectMapper json;

    /** 依赖不可用时受控返回 503；普通业务异常仍由原处理链负责。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException error) {
            if (!isDependencyFailure(error)) throw error;
            log.warn("session dependency unavailable error={} committed={}",
                    error.getClass().getSimpleName(), response.isCommitted());
            // 已发出的响应无法改写；保留异常让容器结束失败传输。
            if (response.isCommitted()) throw error;
            writeUnavailable(response);
        }
    }

    /** 识别被 Servlet 包装的连接、超时及 Redis 执行故障，不吞掉其他程序错误。 */
    private boolean isDependencyFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof QueryTimeoutException
                    || cause instanceof RedisSystemException) return true;
        }
        return false;
    }

    /** 清掉未提交的旧响应体与长度，保留追踪和安全头，直接写 JSON 避免错误分派再次访问 Session。 */
    private void writeUnavailable(HttpServletResponse response) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Content-Type"))
                headers.put(name, List.copyOf(response.getHeaders(name)));
        }
        response.reset();
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(json.writeValueAsString(Result.error(503, "数据服务暂时不可用")));
    }
}
