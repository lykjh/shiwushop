package com.shiwushop.unit.member;

import com.shiwushop.member.model.MembershipLevel;
import com.shiwushop.member.model.MemberRewardPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemberRewardPolicyTest {
    private static final List<MembershipLevel> LEVELS = List.of(
            new MembershipLevel("GOLD", new BigDecimal("5000")),
            new MembershipLevel("ORDINARY", BigDecimal.ZERO),
            new MembershipLevel("PLATINUM", new BigDecimal("15000")),
            new MembershipLevel("SILVER", new BigDecimal("1000")));

    @ParameterizedTest
    @CsvSource({"0,ORDINARY", "999.99,ORDINARY", "1000.00,SILVER", "4999.99,SILVER",
            "5000.00,GOLD", "14999.99,GOLD", "15000.00,PLATINUM", "25000.01,PLATINUM"})
    void mem01UsesExactGrowthAndCanSkipLevels(String amount, String expected) {
        assertEquals(expected, MemberRewardPolicy.levelFor(new BigDecimal(amount), LEVELS));
    }

    @Test
    void missingLevelConfigurationDoesNotSilentlyGrantOrdinary() {
        assertThrows(IllegalStateException.class,
                () -> MemberRewardPolicy.levelFor(BigDecimal.ZERO, List.of()));
    }

    @Test
    void mem02PreservesBothSmallPayments() {
        assertEquals(120, MemberRewardPolicy.pointUnits(new BigDecimal("0.60"))
                + MemberRewardPolicy.pointUnits(new BigDecimal("0.60")));
    }

    @ParameterizedTest
    @CsvSource({"0.00,0", "0.01,1", "80.00,8000", "9999999999999999.99,999999999999999999"})
    void convertsAllSupportedMoneyPrecisely(String amount, long units) {
        assertEquals(units, MemberRewardPolicy.pointUnits(new BigDecimal(amount)));
    }

    @Test
    void unsupportedPrecisionFailsRatherThanTruncating() {
        assertThrows(ArithmeticException.class,
                () -> MemberRewardPolicy.pointUnits(new BigDecimal("0.001")));
    }

    @Test
    void mem03GrowthUsesTheOriginalReceiptDeadline() {
        var receipt = LocalDateTime.parse("2026-11-04T12:00:00.123");
        assertEquals(LocalDateTime.parse("2026-11-11T12:00:00.123"),
                MemberRewardPolicy.growthEligibleAt(receipt));
    }

    @Test
    void expiryUsesTwelveCalendarMonthsIncludingLeapDay() {
        assertEquals(LocalDateTime.parse("2029-02-28T12:00:00"),
                MemberRewardPolicy.pointsExpireAt(LocalDateTime.parse("2028-02-29T12:00:00")));
    }

    @ParameterizedTest
    @CsvSource({"100,0,100,0", "100,60,20,20", "100,80,40,0", "60,40,0,20"})
    void sourceAdjustmentsAndRefundsNeverReverseTheSameGrowthTwice(
            String earned, String retained, String reversed, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(MemberRewardPolicy.additionalGrowthReversal(
                new BigDecimal(earned), new BigDecimal(retained), new BigDecimal(reversed))));
    }
}
