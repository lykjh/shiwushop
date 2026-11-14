package com.shiwushop.trade.vo.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderDetailVO(
    /** 当前记录 ID。 */
    Long id,
    /** 所属店铺 ID。 */
    Long shopId,
    /** 店铺名称。 */
    String shopName,
    /** 订单支付状态：UNPAID 待支付、PAID 已支付、CANCELLED 已取消。 */
    String status,
    /** 现金应付总额，单位元。 */
    BigDecimal totalAmount,
    /** 订单是否仍在等待取消结果确认。 */
    boolean cancelPending,
    /** 下单时冻结的收货地址快照。 */
    AddressSnapshot addressSnapshot,
    /** 当前对象的明细列表。 */
    List<OrderItemVO> items,
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    String businessType,
    /** 结算方式：CASH 现金、ZERO_CASH 零现金、POINTS 积分。 */
    String settlementKind,
    /** 履约门禁：READY 可履约、WAIT_GROUP 等待成团、STOPPED 已停止。 */
    String fulfillmentState,
    /** 下单时冻结的会员等级代码。 */
    String memberLevelCode,
    /** 按普通原价计算的商品总额，单位元。 */
    BigDecimal originalAmount,
    /** 分配给当前对象的优惠金额，单位元。 */
    BigDecimal discountAmount,
    /** 本单或本店应付的积分最小单位数；100 单位为 1 积分。 */
    long pointUnits,
    /** 订单成长及积分的汇总结果。 */
    OrderRewardSummary rewardSummary,
    /** 订单全部有效商品确认收货的时间。 */
    java.time.LocalDateTime fullyReceivedAt,
    /** 按件数计算的订单履约进度。 */
    OrderFulfillmentVO fulfillment
) {
    public OrderDetailVO(Long id, Long shopId, String shopName, String status,
                         BigDecimal totalAmount, boolean cancelPending, AddressSnapshot addressSnapshot,
                         List<OrderItemVO> items, String businessType, String settlementKind,
                         String fulfillmentState, String memberLevelCode, BigDecimal originalAmount,
                         BigDecimal discountAmount, long pointUnits, OrderRewardSummary rewardSummary) {
        this(id, shopId, shopName, status, totalAmount, cancelPending, addressSnapshot, items,
                businessType, settlementKind, fulfillmentState, memberLevelCode, originalAmount,
                discountAmount, pointUnits, rewardSummary, null, null);
    }
    public record AddressSnapshot(
        /** 收货人姓名。 */
        String consignee,
        /** 手机号。 */
        String phone,
        /** 完整地址文本。 */
        String address
    ) {
    }
}
