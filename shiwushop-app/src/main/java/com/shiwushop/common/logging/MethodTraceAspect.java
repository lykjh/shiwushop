package com.shiwushop.common.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Aspect
@Component
@ConditionalOnProperty(prefix = "shop.logging", name = "method-enabled", havingValue = "true")
public class MethodTraceAspect {
    /** 本类日志记录器，供业务事件或故障诊断使用。 */
    private static final Logger log = LoggerFactory.getLogger(MethodTraceAspect.class);
    /** 方法跟踪切点：控制器、服务、mapper 与 cache 包中的 public 方法。 */
    private static final String BUSINESS_METHOD = "execution(public * com.shiwushop..*(..)) && ("
            + "@within(org.springframework.web.bind.annotation.RestController) || "
            + "@within(org.springframework.stereotype.Service) || "
            + "within(com.shiwushop..mapper..*) || within(com.shiwushop..cache..*))";

    /** 只跟踪业务入口与数据访问方法，避免给 final 配置对象创建代理。 */
    @Around(BUSINESS_METHOD)
    public Object trace(ProceedingJoinPoint call) throws Throwable {
        long started = System.nanoTime();
        String declaredBy = call.getSignature().getDeclaringTypeName();
        String owner = declaredBy.startsWith("com.shiwushop.") ? declaredBy
                : ClassUtils.getUserClass(call.getTarget()).getName();
        String method = owner + "." + call.getSignature().getName();
        String module = owner.startsWith("com.shiwushop.common.media.") ? "media"
                : owner.startsWith("com.shiwushop.") ? owner.substring("com.shiwushop.".length()).split("\\.", 2)[0]
                  : "common";
        boolean completed = false;
        try {
            Object result = call.proceed();
            completed = true;
            return result;
        } finally {
            if (completed)
                log.info("method={} outcome=success durationMs={} module={}", method,
                        (System.nanoTime() - started) / 1_000_000, module);
            else
                log.warn("method={} outcome=failure durationMs={} module={}", method,
                        (System.nanoTime() - started) / 1_000_000, module);
        }
    }

    /** 由框架观察异常类型，业务异常继续原样传播，不捕获参数或异常消息。 */
    @AfterThrowing(pointcut = BUSINESS_METHOD,
            throwing = "failure")
    public void failed(JoinPoint call, Throwable failure) {
        log.warn("method={} error={}", call.getSignature().toLongString(), failure.getClass().getSimpleName());
    }
}
