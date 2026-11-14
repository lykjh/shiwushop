package com.shiwushop.unit.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.dto.ShopChoice;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.service.MarketingPricingService;
import com.shiwushop.marketing.service.impl.MarketingPricingServiceImpl;
import com.shiwushop.marketing.vo.ActivityPublicVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketingPricingServiceTest {
    final MarketingPricingMapper mapper = mock(MarketingPricingMapper.class);
    final MarketingActivityMapper activities = mock(MarketingActivityMapper.class);
    final MarketingActivityPublicMapper details = mock(MarketingActivityPublicMapper.class);
    final ObjectMapper json = new ObjectMapper();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.ofHours(8));
    final MarketingPricingServiceImpl service = new MarketingPricingServiceImpl(mapper, activities, details,
            new ActivityContentHash(json), json, clock);

    MarketingPricingService.Line line(long sku, int count, String price) {
        return new MarketingPricingService.Line(sku, 10L, 99L, count, new BigDecimal(price));
    }

    PricingScope price(long id, String kind, String amount) {
        var p = new PricingScope();
        p.setActivityId(id);
        p.setActivityCode("ACT-" + id);
        p.setActivityType(kind);
        p.setEligibleLevelCodes("[\"GOLD\"]");
        p.setActivitySkuId(id * 10);
        p.setSkuId(1L);
        p.setPrice(new BigDecimal(amount));
        return p;
    }

    UserCoupon coupon(long id, long activity, String face, String threshold, LocalDateTime expiry) {
        var c = new UserCoupon();
        c.setId(id);
        c.setActivityId(activity);
        c.setFaceAmount(new BigDecimal(face));
        c.setThresholdAmount(new BigDecimal(threshold));
        c.setExpiresAt(expiry);
        return c;
    }

    void resources(Long... ids) {
        when(mapper.budgets(anyList(), eq(false))).thenReturn(
                Arrays.stream(ids).map(id -> new MarketingBudget(id, id, "MERCHANT", 10L,
                                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, 5000, LocalDateTime.now(clock), 0L))
                        .toList());
        for (long id : ids)
            when(mapper.scopeIds(id, false)).thenReturn(List.of(1L, 2L));
    }

    @Test
    void documentedPriceOrderIsSkuThenReductionThenCoupon() {
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false))).thenReturn(
                List.of(price(1, "MEMBER_PRICE", "95"), price(2, "LIMITED_PRICE", "90")));
        var reduction = new MarketingActivity();
        reduction.setId(3L);
        reduction.setActivityType("FULL_REDUCTION");
        reduction.setStatus("RUNNING");
        reduction.setStartAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        reduction.setEndAt(LocalDateTime.of(2035, 1, 1, 0, 0));
        reduction.setEligibleLevelCodes("[\"GOLD\"]");
        when(activities.find(3L)).thenReturn(reduction);
        when(details.tiers(3L)).thenReturn(
                List.of(new ActivityPublicVO.Tier(30L, new BigDecimal("180"), new BigDecimal("10"), null, null)));
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(40, 4, "5", "170", LocalDateTime.now(clock).plusDays(1))));
        resources(1L, 2L, 3L, 4L);
        var result = service.price(1L, "GOLD", List.of(line(1, 2, "100")),
                List.of(new ShopChoice(10L, "SELECT", 40L, 3L, null)), "address", false);
        assertEquals(0, new BigDecimal("165").compareTo(result.quote().totalAmount()));
        assertEquals(List.of("LIMITED", "FULL_REDUCTION", "COUPON"),
                result.allocations().stream().map(MarketingPricingService.Allocation::kind).toList());
        assertEquals(16500, result.quote().rewardPreview().pointUnits());
        assertEquals(0, new BigDecimal("35").compareTo(result.quote().discountAmount()));
    }

    @Test
    void equalPricesPreferOrdinaryThenMemberThenLimited() {
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false))).thenReturn(
                List.of(price(2, "LIMITED_PRICE", "90"), price(1, "MEMBER_PRICE", "90")));
        resources(1L, 2L);
        var member = service.price(1L, "GOLD", List.of(line(1, 1, "100")), List.of(), "address", false);
        assertEquals("MEMBER", member.allocations().getFirst().kind());
        var ordinary = service.price(1L, "GOLD", List.of(line(1, 1, "90")), List.of(), "address", false);
        assertTrue(ordinary.allocations().isEmpty());
    }

    @Test
    void couponAllocationIsProportionalAndAutoTiesUseExpiryThenId() {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(43, 4, "10", "299", expiry.plusDays(1)),
                        coupon(42, 4, "10", "299", expiry), coupon(41, 4, "10", "299", expiry)));
        resources(4L);
        var result = service.price(1L, "GOLD", List.of(line(1, 1, "100"), line(2, 1, "200")),
                List.of(new ShopChoice(10L, "AUTO", null, null, null)), "address", false);
        assertEquals(41L, result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(List.of(new BigDecimal("3.33"), new BigDecimal("6.67")),
                result.allocations().stream().map(MarketingPricingService.Allocation::amount).toList());
    }

    @Test
    void autoPrefersGreaterSavingWithinCouponScopeBeforeExpiryOrId() {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(40, 4, "4.00", "4.00", expiry),
                        coupon(50, 5, "6.00", "6.00", expiry.plusDays(1))));
        resources(4L, 5L);
        when(mapper.scopeIds(4L, false)).thenReturn(List.of(1L));
        when(mapper.scopeIds(5L, false)).thenReturn(List.of(2L));
        var lines = List.of(line(1, 1, "5.00"), line(2, 1, "10.00"));

        var auto = service.price(1L, "GOLD", lines,
                List.of(new ShopChoice(10L, "AUTO", null, null, null)), "address", false);

        assertEquals(50L, auto.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal("9.00"), auto.quote().totalAmount());
        assertEquals(List.of(new BigDecimal("4.00"), new BigDecimal("6.00")),
                auto.quote().couponCandidates().stream().map(c -> c.savingAmount()).toList());
        assertEquals(2L, auto.allocations().getFirst().skuId());
        assertEquals(new BigDecimal("6.00"), auto.allocations().getFirst().amount());
        var selected = service.price(1L, "GOLD", lines,
                List.of(new ShopChoice(10L, "SELECT", 50L, null, null)), "address", false);
        assertEquals(auto.quote().quoteHash(), selected.quote().quoteHash());
        assertEquals(auto.quote().totalAmount(), selected.quote().totalAmount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalSavingsPreferEarlierExpiryBeforeSmallerId(boolean reverseInput) {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        var available = new ArrayList<>(List.of(coupon(40, 4, "5.00", "5.00", expiry.plusDays(1)),
                coupon(50, 4, "5.0", "5.00", expiry)));
        if (reverseInput)
            Collections.reverse(available);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(available);
        resources(4L);

        var result = service.price(1L, "GOLD", List.of(line(1, 1, "10.00")),
                List.of(new ShopChoice(10L, "AUTO", null, null, null)), "address", false);

        assertEquals(50L, result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal("5.00"), result.quote().totalAmount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalSavingsAndExpiryPreferSmallerIdRegardlessOfInputOrder(boolean reverseInput) {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        var available = new ArrayList<>(List.of(coupon(50, 4, "5.0", "5.00", expiry),
                coupon(40, 4, "5.00", "5.00", expiry)));
        if (reverseInput)
            Collections.reverse(available);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(available);
        resources(4L);

        var result = service.price(1L, "GOLD", List.of(line(1, 1, "10.00")),
                List.of(new ShopChoice(10L, "AUTO", null, null, null)), "address", false);

        assertEquals(40L, result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal("5.00"), result.quote().totalAmount());
    }

    @Test
    void autoOnlyRanksCouponsStillUsableAfterMemberPriceAndFullReduction() {
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false)))
                .thenReturn(List.of(price(1, "MEMBER_PRICE", "12.00")));
        var reduction = new MarketingActivity();
        reduction.setId(3L);
        reduction.setActivityType("FULL_REDUCTION");
        reduction.setStatus("RUNNING");
        reduction.setStartAt(LocalDateTime.now(clock).minusDays(1));
        reduction.setEndAt(LocalDateTime.now(clock).plusDays(1));
        reduction.setEligibleLevelCodes("[\"GOLD\"]");
        when(activities.find(3L)).thenReturn(reduction);
        when(details.tiers(3L)).thenReturn(
                List.of(new ActivityPublicVO.Tier(30L, new BigDecimal("12.00"), new BigDecimal("5.00"), null, null)));
        var expiry = LocalDateTime.now(clock).plusDays(1);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(40, 4, "10.00", "10.00", expiry),
                        coupon(50, 5, "5.00", "5.00", expiry.plusDays(1))));
        resources(1L, 3L, 4L, 5L);

        var result = service.price(1L, "GOLD", List.of(line(1, 1, "20.00")),
                List.of(new ShopChoice(10L, "AUTO", null, 3L, null)), "address", false);

        assertEquals(50L, result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal("2.00"), result.quote().totalAmount());
        var rejected = result.quote().couponCandidates().stream()
                .filter(c -> c.userCouponId().equals(40L)).findFirst().orElseThrow();
        assertFalse(rejected.usable());
        assertEquals("COUPON_NOT_USABLE", rejected.reason());
        assertEquals(0, rejected.savingAmount().signum());
        assertEquals(List.of("MEMBER", "FULL_REDUCTION", "COUPON"),
                result.allocations().stream().map(MarketingPricingService.Allocation::kind).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void autoLeavesSelectionEmptyWhenNoCouponIsUsable(boolean hasIneligibleCoupon) {
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(hasIneligibleCoupon
                ? List.of(coupon(40, 4, "2.00", "5.00", LocalDateTime.now(clock).plusDays(1))) : List.of());
        resources(4L);

        var result = service.price(1L, "GOLD", List.of(line(1, 1, "4.00")),
                List.of(new ShopChoice(10L, "AUTO", null, null, null)), "address", false);

        assertNull(result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal("4.00"), result.quote().totalAmount());
        assertTrue(result.allocations().isEmpty());
        assertTrue(result.quote().couponCandidates().stream().noneMatch(c -> c.usable()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "SELECT"})
    void explicitCouponChoiceIsNotReplacedByAutoRecommendation(String mode) {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(40, 4, "2.00", "2.00", expiry), coupon(50, 4, "5.00", "5.00", expiry)));
        resources(4L);
        Long selectedId = mode.equals("SELECT") ? 40L : null;

        var result = service.price(1L, "GOLD", List.of(line(1, 1, "10.00")),
                List.of(new ShopChoice(10L, mode, selectedId, null, null)), "address", false);

        assertEquals(selectedId, result.quote().shops().getFirst().selectedUserCouponId());
        assertEquals(new BigDecimal(mode.equals("SELECT") ? "8.00" : "10.00"), result.quote().totalAmount());
    }

    @Test
    void twoCentCouponSplitsOneCentOfPlatformFundingAcrossTwoItems() {
        var expiry = LocalDateTime.now(clock).plusDays(1);
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(41, 4, "0.02", "0.02", expiry)));
        resources(4L);
        var result = service.price(1L, "GOLD", List.of(line(2, 1, "1"), line(1, 1, "1")),
                List.of(new ShopChoice(10L, "SELECT", 41L, null, null)), "address", false);
        assertEquals(List.of(1L, 2L),
                result.allocations().stream().map(MarketingPricingService.Allocation::skuId).toList());
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.00")),
                result.allocations().stream().map(MarketingPricingService.Allocation::platformAmount).toList());
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.01")),
                result.allocations().stream().map(MarketingPricingService.Allocation::amount).toList());
    }

    @Test
    void excludedItemsCannotCompleteCouponThresholdAndSelfShopRewardsAreExcluded() {
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(
                List.of(coupon(40, 4, "10", "199", LocalDateTime.now(clock).plusDays(1))));
        resources(4L);
        when(mapper.scopeIds(4L, false)).thenReturn(List.of(1L));
        var error = assertThrows(BusinessException.class,
                () -> service.price(1L, "GOLD", List.of(line(1, 1, "150"), line(2, 1, "100")),
                        List.of(new ShopChoice(10L, "SELECT", 40L, null, null)), "address", false));
        assertEquals(409, error.getCode());
        var own = new MarketingPricingService.Line(1L, 10L, 1L, 1, new BigDecimal("150"));
        var quote = service.price(1L, "GOLD", List.of(own), List.of(), "address", false).quote();
        assertEquals(0, quote.rewardPreview().pointUnits());
        assertEquals("SELF_SHOP_OWNER", quote.rewardPreview().exclusionReason());
    }

    @Test
    void quoteChecksSharedBuyQuotaAcrossSkus() {
        var first = price(2, "LIMITED_PRICE", "90");
        var second = price(2, "LIMITED_PRICE", "85");
        first.setPerUserLimit(1);
        second.setPerUserLimit(2);
        second.setSkuId(2L);
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false))).thenReturn(List.of(first, second));
        var error = assertThrows(BusinessException.class, () -> service.price(1L, "GOLD",
                List.of(line(1, 1, "100"), line(2, 1, "100")), List.of(), "address", false));
        assertEquals(Map.of("reason", "PURCHASE_LIMIT_REACHED"), error.getData());
        verify(mapper).usedQuota("ACT-2", 1L, false);
    }

    @Test
    void limitedQuotaUsesStableActivityCodeAndNeverSilentlyDropsTheChosenDiscount() {
        var limited = price(2, "LIMITED_PRICE", "90");
        limited.setPerUserLimit(2);
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false))).thenReturn(List.of(limited));
        resources(2L);
        when(mapper.usedQuota("ACT-2", 1L, false)).thenReturn(2);
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.price(1L, "GOLD", List.of(line(1, 1, "100")), List.of(), "address", false)).getCode());
    }

    @Test
    void memberPriceWithoutItsShopBudgetFailsClosed() {
        when(mapper.prices(anyList(), eq("GOLD"), any(), eq(false)))
                .thenReturn(List.of(price(1, "MEMBER_PRICE", "90")));
        var error = assertThrows(BusinessException.class,
                () -> service.price(1L, "GOLD", List.of(line(1, 1, "100")), List.of(), "address", false));
        assertEquals(409, error.getCode());
        assertEquals(Map.of("reason", "ACTIVITY_NOT_AVAILABLE"), error.getData());
    }

    @Test
    void selectedCouponWithoutItsShopBudgetIsUnavailable() {
        when(mapper.available(eq(1L), any(), eq(false), isNull())).thenReturn(List.of(
                coupon(40, 4, "5", "10", LocalDateTime.now(clock).plusDays(1))));
        var error = assertThrows(BusinessException.class, () -> service.price(1L, "GOLD",
                List.of(line(1, 1, "100")), List.of(new ShopChoice(10L, "SELECT", 40L, null, null)),
                "address", false));
        assertEquals(409, error.getCode());
        assertEquals(Map.of("reason", "COUPON_NOT_USABLE"), error.getData());
    }
}
