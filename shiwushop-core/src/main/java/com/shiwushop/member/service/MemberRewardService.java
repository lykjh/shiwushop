package com.shiwushop.member.service;

import com.shiwushop.member.dto.RewardLine;

import java.time.LocalDateTime;
import java.util.List;

/** 所有权益写入加入调用方事务；调用方先持有订单锁，会员实现再锁账户。 */
public interface MemberRewardService {
    /** 新结算在 SKU 锁之前取得会员账户锁，随后 orderCreated 复用当前事务。 */
    String prepareOrder(Long userId);

    void orderCreated(Long userId, Long ownerAtOrder, LocalDateTime createdAt, List<RewardLine> lines);

    void paymentConfirmed(Long userId, Long orderId, LocalDateTime paidAt, List<RewardLine> lines);

    void receiptConfirmed(Long orderId, LocalDateTime confirmedAt);

    void growthDue(Long userId, Long orderId, LocalDateTime confirmedAt, boolean unresolvedAfterSale,
                   List<RewardLine> lines);

    void refundConfirmed(Long userId, Long orderId, Long caseId, List<RewardLine> lines);
}
