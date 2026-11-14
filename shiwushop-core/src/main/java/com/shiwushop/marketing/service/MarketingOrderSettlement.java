package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.MarketingOrderSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 由持有订单锁的调用方在同一事务内调用；状态去重归订单／售后层，不能直接重放结算。 */
public interface MarketingOrderSettlement {
    MarketingOrderSource prepareSource(Long userId, String businessType, Long activitySkuId,
                                       int quantity, Long groupId, Long bargainId, String requestKey);

    /** 在订单锁之前先锁团/砍价根，再锁参与根；普通订单返回 null。 */
    MarketingOrderSource lockSource(Long orderId);

    /** items 映射 SKU 与已创建的订单明细；discounts 必须来自服务端重新确认的报价。 */
    void reserve(long order, long shop, long user, LocalDateTime deadline, Long coupon,
                 Map<Long, Long> items, List<MarketingPricingService.Allocation> discounts, Long participationId);

    /** paid=true 将预占转支出，false 释放未付资源；已付款后的撤销走 refund。 */
    void finish(long order, long user, boolean paid);

    /** sale 是已确认退款的售后单 ID，分摊只读取原订单快照。 */
    void refund(long order, long sale);
}
