package com.shiwushop.common;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Aspect
@Component
@ConditionalOnProperty(prefix = "shop.logging", name = "method-enabled", havingValue = "true")
public class MethodTraceAspect {
    private static final Logger log = LoggerFactory.getLogger(MethodTraceAspect.class);

    @Around("execution(public * com.shiwushop..*(..)) && !within(com.shiwushop.common.MethodTraceAspect)")
    public Object trace(ProceedingJoinPoint call) throws Throwable {
        long started = System.nanoTime();
        String declaredBy = call.getSignature().getDeclaringTypeName();
        String owner = declaredBy.startsWith("com.shiwushop.") ? declaredBy
                : ClassUtils.getUserClass(call.getTarget()).getName();
        String method = owner + "." + call.getSignature().getName();
        try {
            Object result = call.proceed();
            log.info("method={} outcome=success durationMs={}", method, (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (Throwable failure) {
            // 仅记录异常类型，避免参数和异常消息携带账号、密码或交易数据。
            log.warn("method={} outcome=failure error={} durationMs={}", method,
                    failure.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
    }
}
