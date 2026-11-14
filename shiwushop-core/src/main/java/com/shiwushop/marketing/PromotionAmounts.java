package com.shiwushop.marketing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** 调用方已按shopId、skuId、itemKind、输入序号排好稳定顺序。 */
public final class PromotionAmounts {
    private PromotionAmounts() {
    }

    /** 比例以万分比表示，平台份额向下取整到分，剩余分值由商家承担。 */
    public static BigDecimal platformShare(BigDecimal discount, int basisPoints) {
        return discount.multiply(BigDecimal.valueOf(basisPoints)).movePointLeft(4)
                .setScale(2, java.math.RoundingMode.DOWN);
    }

    /** 整分按最大余数分配；余数相同沿用输入顺序，确保分项之和等于总额。 */
    public static List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> weights) {
        var shares = new ArrayList<BigInteger>(Collections.nCopies(weights.size(), BigInteger.ZERO));
        if (amount.signum() == 0)
            return shares.stream().map(v -> new BigDecimal(v, 2)).toList();
        BigInteger cents = amount.movePointRight(2).toBigIntegerExact();
        var weightsInCents = weights.stream().map(w -> w.movePointRight(2).toBigIntegerExact()).toList();
        BigInteger total = weightsInCents.stream().reduce(BigInteger.ZERO, BigInteger::add), assigned = BigInteger.ZERO;
        var remainders = new ArrayList<BigInteger>();
        for (int index = 0; index < weights.size(); index++) {
            var divided = cents.multiply(weightsInCents.get(index)).divideAndRemainder(total);
            shares.set(index, divided[0]);
            assigned = assigned.add(divided[0]);
            remainders.add(divided[1]);
        }
        var order = new ArrayList<Integer>();
        for (int index = 0; index < weights.size(); index++)
            order.add(index);
        order.sort(Comparator.<Integer, BigInteger>comparing(remainders::get).reversed().thenComparingInt(i -> i));
        int remaining = cents.subtract(assigned).intValueExact();
        for (int index = 0; index < remaining; index++) {
            int recipient = order.get(index);
            shares.set(recipient, shares.get(recipient).add(BigInteger.ONE));
        }
        return shares.stream().map(v -> new BigDecimal(v, 2)).toList();
    }

    /** 单位序号从1开始，余分固定分给最前单位，不按当前已退数量重新排列。 */
    public static BigDecimal units(BigDecimal amount, int quantity, int from, int to) {
        long cents = amount.movePointRight(2).longValueExact();
        long base = cents / quantity, remainder = cents % quantity;
        long extra = Math.max(0, Math.min(to, remainder) - from + 1);
        return BigDecimal.valueOf(base * (to - from + 1L) + extra, 2);
    }
}
