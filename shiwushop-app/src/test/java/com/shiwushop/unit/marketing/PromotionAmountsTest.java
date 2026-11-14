package com.shiwushop.unit.marketing;

import com.shiwushop.marketing.PromotionAmounts;
import com.shiwushop.marketing.RefundUnits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PromotionAmountsTest {
    @Test
    void withdrawnUnitIsReusedWithoutReallocatingTheOccupiedMiddleUnit() {
        var occupied = new RefundUnits.Range(2, 2, 3, 0);
        var refund = RefundUnits.select(new BigDecimal("0.10"), 3, 2, List.of(occupied));
        assertEquals(List.of(new RefundUnits.Range(1, 1, 4, 0),
                new RefundUnits.Range(3, 3, 3, 0)), refund);
    }

    @Test
    void documentedCouponExampleAndEqualRemainderUseStableOrder() {
        assertEquals(List.of(new BigDecimal("3.33"), new BigDecimal("6.67")),
                PromotionAmounts.allocate(new BigDecimal("10.00"),
                        List.of(new BigDecimal("100"), new BigDecimal("200"))));
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.00")),
                PromotionAmounts.allocate(new BigDecimal("0.01"), List.of(BigDecimal.ONE, BigDecimal.ONE)));
    }

    @Test
    void zeroRowsDoNotReceiveCentsAndFullDiscountNeverExceedsTheLine() {
        assertEquals(List.of(new BigDecimal("0.00"), new BigDecimal("0.03")),
                PromotionAmounts.allocate(new BigDecimal("0.03"), List.of(BigDecimal.ZERO, new BigDecimal("0.03"))));
        assertEquals(List.of(new BigDecimal("0.00")),
                PromotionAmounts.allocate(BigDecimal.ZERO, List.of(BigDecimal.ZERO)));
    }

    @Test
    void seededAmountsConserveEveryCentWithoutOverDiscountingAnyLine() {
        var random = new Random(20261104);
        for (int sample = 0; sample < 1000; sample++) {
            var weights = new ArrayList<BigDecimal>();
            long total = 0;
            for (int i = 0; i < 1 + random.nextInt(20); i++) {
                int cents = 1 + random.nextInt(100000);
                total += cents;
                weights.add(BigDecimal.valueOf(cents, 2));
            }
            var amount = BigDecimal.valueOf(random.nextLong(total + 1), 2);
            var allocated = PromotionAmounts.allocate(amount, weights);
            assertEquals(amount, allocated.stream().reduce(new BigDecimal("0.00"), BigDecimal::add));
            for (int i = 0; i < weights.size(); i++)
                assertTrue(allocated.get(i).signum() >= 0 && allocated.get(i).compareTo(weights.get(i)) <= 0);
        }
    }

    @Test
    void maximumColumnAmountDoesNotOverflowIntermediateProducts() {
        var max = new BigDecimal("9999999999999999.99");
        assertEquals(List.of(new BigDecimal("5000000000000000.00"), new BigDecimal("4999999999999999.99")),
                PromotionAmounts.allocate(max, List.of(max, max)));
    }

    @Test
    void refundUnitIdentitySurvivesWithdrawalAndReapplication() {
        var total = new BigDecimal("10.00");
        assertEquals(new BigDecimal("3.34"), PromotionAmounts.units(total, 3, 1, 1));
        assertEquals(new BigDecimal("6.66"), PromotionAmounts.units(total, 3, 2, 3));
        assertEquals(total, PromotionAmounts.units(total, 3, 1, 1).add(PromotionAmounts.units(total, 3, 2, 3)));
        assertEquals(new BigDecimal("3.34"), PromotionAmounts.units(total, 3, 1, 1));
    }
}
