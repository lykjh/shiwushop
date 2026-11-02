package com.shiwushop.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 记录已完成的业务状态变化；事务回滚时不输出成功事件。 */
public final class BusinessEventLog {
    private static final Logger log = LoggerFactory.getLogger(BusinessEventLog.class);

    private BusinessEventLog() {}

    /** 只记录固定事件名和内部数字 ID，避免把请求参数、密码、地址或渠道凭证写入日志。 */
    public static void committed(String module, String action, String entity, Long entityId) {
        String requestId = MDC.get("requestId");
        String eventId = MDC.get("eventId");
        Runnable write = () -> {
            try (MDC.MDCCloseable request = MDC.putCloseable("requestId", requestId);
                 MDC.MDCCloseable event = MDC.putCloseable("eventId", eventId)) {
                log.info("business module={} action={} entity={} entityId={} outcome=committed",
                        module, action, entity, entityId);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }
}
