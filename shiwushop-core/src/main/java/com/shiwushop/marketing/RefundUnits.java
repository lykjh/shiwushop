package com.shiwushop.marketing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** 用原明细中从 1 开始的闭区间标识退货单位，保持分批退款的余分归属稳定。 */
public final class RefundUnits {
    private RefundUnits() {
    }

    public record Range(int from, int to, long cashMinor, long pointUnits) {
    }

    /** 从尚未占用的单位中选取 requested 件；occupied 由售后层在订单锁内提供。 */
    public static List<Range> select(BigDecimal payable, int quantity, int requested, List<Range> occupied) {
        var used = new BitSet(quantity + 1);
        for (var range : occupied)
            used.set(range.from(), range.to() + 1);
        var ranges = new ArrayList<Range>();
        for (int cursor = 1; requested > 0; ) {
            int from = used.nextClearBit(cursor);
            if (from > quantity)
                throw new IllegalStateException("售后占用区间与可退数量不一致");
            int nextOccupied = used.nextSetBit(from);
            int last = nextOccupied < 0 ? quantity : nextOccupied - 1;
            int to = Math.min(last, from + requested - 1);
            long cash = PromotionAmounts.units(payable, quantity, from, to).movePointRight(2).longValueExact();
            ranges.add(new Range(from, to, cash, 0));
            requested -= to - from + 1;
            cursor = to + 1;
        }
        return ranges;
    }
}
