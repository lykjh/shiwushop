package com.shiwushop.common.logging;

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

    /** 按需记录公开方法耗时与结果；不输出参数、返回值或异常消息。 */
    @Around("execution(public * com.shiwushop..*(..)) && !within(com.shiwushop.common.logging.MethodTraceAspect)")
    public Object trace(ProceedingJoinPoint call) throws Throwable {
        long started = System.nanoTime();
        String declaredBy = call.getSignature().getDeclaringTypeName();
        String owner = declaredBy.startsWith("com.shiwushop.") ? declaredBy
                : ClassUtils.getUserClass(call.getTarget()).getName();
        String method = owner + "." + call.getSignature().getName();
        String module = owner.startsWith("com.shiwushop.account.") ? "account"
                : owner.startsWith("com.shiwushop.catalog.") ? "catalog"
                : owner.startsWith("com.shiwushop.commerce.") ? "commerce"
                : owner.startsWith("com.shiwushop.common.media.") ? "media" : "common";
        try {
            Object result = call.proceed();
            log.info("method={} outcome=success durationMs={} module={}", method,
                    (System.nanoTime() - started) / 1_000_000, module);
            return result;
        } catch (Throwable failure) {
            // 仅记录异常类型，避免参数和异常消息携带账号、密码或交易数据。
            log.warn("method={} outcome=failure error={} durationMs={} module={}", method,
                    failure.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000, module);
            throw failure;
        }
    }
}
