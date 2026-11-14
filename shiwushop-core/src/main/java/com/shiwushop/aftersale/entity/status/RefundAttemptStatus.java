package com.shiwushop.aftersale.entity.status;

public enum RefundAttemptStatus {
    /** 退款请求待处理或确认。 */
    PENDING,
    /** 渠道结果未确定，需要继续查询确认。 */
    UNKNOWN,
    /** 退款成功。 */
    SUCCESS,
    /** 退款失败。 */
    FAILED
}
