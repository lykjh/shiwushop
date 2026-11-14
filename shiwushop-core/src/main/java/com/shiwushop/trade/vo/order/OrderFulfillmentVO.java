package com.shiwushop.trade.vo.order;

import lombok.Data;

/** 按有效件数派生发货与收货进度；退款件数保留原履约来源。 */
@Data
public class OrderFulfillmentVO {
    /** 订单商品总件数。 */
    private int totalQuantity;
    /** 累计已发货件数。 */
    private int shippedQuantity;
    /** 累计已确认收货件数。 */
    private int confirmedQuantity;
    /** 已退款且来源为未发货的件数。 */
    private int refundedUnshippedQuantity;
    /** 已退款且来源为已发货包裹的件数。 */
    private int refundedShippedQuantity;
    /** 已退款且原包裹已确认收货的件数。 */
    private int refundedConfirmedQuantity;
    /** 尚无法归属原履约来源的退款件数。 */
    private int unknownRefundedQuantity;
    /** 尚待核对明细分配的包裹数。 */
    private int unresolvedAllocationCount;

    public int getRequiredQuantity() {
        return totalQuantity - refundedUnshippedQuantity - refundedShippedQuantity - unknownRefundedQuantity;
    }

    public int getAvailableToShipQuantity() {
        return totalQuantity - shippedQuantity - refundedUnshippedQuantity;
    }

    public int getEffectiveShippedQuantity() {
        return shippedQuantity - refundedShippedQuantity;
    }

    public int getEffectiveConfirmedQuantity() {
        return confirmedQuantity - refundedConfirmedQuantity;
    }

    public String getShippingProgress() {
        if (unresolvedAllocationCount > 0 || unknownRefundedQuantity > 0)
            return "NEEDS_REVIEW";
        int required = getRequiredQuantity();
        if (required == 0)
            return "NO_REMAINING";
        if (getEffectiveShippedQuantity() == 0)
            return "NOT_SHIPPED";
        return getEffectiveShippedQuantity() == required ? "SHIPPED" : "PARTIAL";
    }

    public String getReceiptProgress() {
        if (unresolvedAllocationCount > 0 || unknownRefundedQuantity > 0)
            return "NEEDS_REVIEW";
        int required = getRequiredQuantity();
        if (required == 0)
            return "NO_REMAINING";
        if (getEffectiveConfirmedQuantity() == 0)
            return "NOT_RECEIVED";
        return getEffectiveConfirmedQuantity() == required ? "RECEIVED" : "PARTIAL";
    }
}
