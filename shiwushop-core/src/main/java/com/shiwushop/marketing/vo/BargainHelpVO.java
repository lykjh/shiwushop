package com.shiwushop.marketing.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BargainHelpVO(
    /** 砍价实例 ID。 */
    long bargainId,
    /** 本次砍价助力的步骤序号。 */
    int stepNo,
    /** 本次助力降低的金额，单位元。 */
    BigDecimal decreaseAmount,
    /** 砍价当前金额，单位元。 */
    BigDecimal currentAmount,
    /** 当前已成功助力次数。 */
    int helpCount,
    /** 砍价状态：ACTIVE 助力中、QUALIFIED 可购买、ORDERED 已下单、EXPIRED 过期、CLOSED 关闭。 */
    String status,
    /** 砍价成功后的购买截止时间。 */
    OffsetDateTime purchaseExpiresAt
) {
}
