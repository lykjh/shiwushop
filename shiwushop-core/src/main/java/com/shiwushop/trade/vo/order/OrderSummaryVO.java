package com.shiwushop.trade.vo.order;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderSummaryVO {
    /** 当前记录 ID。 */
    private Long id;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 店铺名称。 */
    private String shopName;
    /** 现金应付总额，单位元。 */
    private BigDecimal totalAmount;
    /** 订单支付状态：UNPAID 待支付、PAID 已支付、CANCELLED 已取消。 */
    private String status;
    /** 订单明细行数，不是商品总件数。 */
    private Long itemCount;
    /** 本单或本店应付的积分最小单位数；100 单位为 1 积分。 */
    private long pointUnits;
    /** 订单成长及积分的汇总结果。 */
    private OrderRewardSummary rewardSummary;
    private String businessType, // 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。
        settlementKind, // 结算方式：CASH 现金、ZERO_CASH 零现金、POINTS 积分。
        fulfillmentState, // 履约门禁：READY 可履约、WAIT_GROUP 等待成团、STOPPED 已停止。
        memberLevelCode; // 下单时冻结的会员等级代码。
    private BigDecimal originalAmount, // 按普通原价计算的商品总额，单位元。
        discountAmount; // 分配给当前对象的优惠金额，单位元。
}
