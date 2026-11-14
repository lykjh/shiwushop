package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.PromotionAmounts;
import com.shiwushop.marketing.dto.ShopChoice;
import com.shiwushop.marketing.mapper.*;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.service.MarketingPricingService;
import com.shiwushop.marketing.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketingPricingServiceImpl implements MarketingPricingService {
    private final MarketingPricingMapper mapper;
    private final MarketingActivityMapper activities;
    private final MarketingActivityPublicMapper details;
    private final ActivityContentHash hash;
    private final ObjectMapper json;
    private final Clock businessClock;
    /** 报价计算使用的零金额，单位为元，保留两位小数。 */
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    /** 报价原始总金额上限，单位为元，与 DECIMAL(18,2) 的最大值一致。 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    private record Raw(Long activity, String kind, Long coupon, Long tier, BigDecimal amount, String snapshot) {
    }

    private record ItemDiscount(Long sku, Raw benefit) {
    }

    private static class Item {
        final Line line;
        BigDecimal unitPrice, payable;
        Long activitySku;
        final List<Raw> discounts = new ArrayList<>();

        Item(Line line) {
            this.line = line;
            this.unitPrice = line.ordinaryPrice();
            this.payable = unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
        }

        BigDecimal original() {
            return line.ordinaryPrice().multiply(BigDecimal.valueOf(line.quantity()));
        }

        QuoteVO.Item view() {
            return new QuoteVO.Item(line.skuId(), line.quantity(), "SALE", line.ordinaryPrice(), unitPrice,
                    unitPrice.multiply(BigDecimal.valueOf(line.quantity())), original().subtract(payable), payable,
                    activitySku,
                    discounts.stream().map(d -> new QuoteVO.Benefit(d.activity(), d.kind(), d.amount())).toList());
        }
    }

    private static void conflict(String reason, String message) {
        throw new BusinessException(409, message, Map.of("reason", reason), null);
    }

    private boolean eligible(String codes, String level) {
        return Arrays.asList(json.readValue(codes, String[].class)).contains(level);
    }

    /** 预览与下单复用同一算法；lock=true 在调用方结算事务内锁定活动、限额及预算。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Price price(Long user, String level, List<Line> lines, List<ShopChoice> choices, String addressHash,
                       boolean lock) {
        var now = LocalDateTime.now(businessClock);
        // 固定明细顺序，使余分分配和报价摘要不受客户端提交顺序影响。
        var byShop = new TreeMap<Long, List<Item>>();
        lines.stream().sorted(Comparator.comparing(Line::shopId).thenComparing(Line::skuId))
                .forEach(line -> byShop.computeIfAbsent(line.shopId(), k -> new ArrayList<>()).add(new Item(line)));
        var selections = new HashMap<Long, ShopChoice>();
        for (var choice : choices) {
            if (!byShop.containsKey(choice.shopId()) || selections.put(choice.shopId(), choice) != null
                    || choice.couponMode().equals("SELECT") != (choice.userCouponId() != null))
                throw new BusinessException(400, "店铺优惠选择重复、无对应商品或券参数不匹配",
                        Map.of("reason", "INVALID_ARGUMENT"), null);
            if (choice.giftActivityId() != null)
                conflict("GIFT_STOCK_NOT_ENOUGH", "满赠库存链路尚未就绪");
        }
        // 结算已持有账户/SKU锁；先活动范围，再额度、券、预算。券的不可变归属先只读定位。
        var selectedCoupons = choices.stream().map(ShopChoice::userCouponId).filter(Objects::nonNull).toList();
        var coupons = lock ? (selectedCoupons.isEmpty() ? List.<UserCoupon>of() : mapper.couponMetadata(user,
                selectedCoupons))
                : mapper.available(user, now, false, null);
        var prices = mapper.prices(lines.stream().map(Line::skuId).toList(), level, now, lock);
        var reductions = new HashMap<Long, MarketingActivity>();
        for (long id : choices.stream().map(ShopChoice::reductionActivityId).filter(Objects::nonNull).distinct()
                .sorted().toList()) {
            var activity = lock ? activities.share(id) : activities.find(id);
            if (activity == null || !activity.getActivityType().equals("FULL_REDUCTION")
                    || !Set.of("APPROVED", "RUNNING").contains(activity.getStatus()) ||
                    now.isBefore(activity.getStartAt()) || !now.isBefore(activity.getEndAt()))
                conflict("ACTIVITY_NOT_AVAILABLE", "满减活动当前不可用");
            if (!eligible(activity.getEligibleLevelCodes(), level))
                conflict("LEVEL_NOT_ELIGIBLE", "等级不符合满减条件");
            reductions.put(id, activity);
        }
        var activityIds = new TreeSet<Long>();
        prices.forEach(p -> activityIds.add(p.getActivityId()));
        coupons.forEach(c -> activityIds.add(c.getActivityId()));
        activityIds.addAll(reductions.keySet());
        var scopes = new HashMap<Long, Set<Long>>();
        for (long id : activityIds)
            scopes.put(id, new HashSet<>(mapper.scopeIds(id, lock)));
        var limitedQuantities = new TreeMap<String, Long>();
        var purchaseLimits = new HashMap<String, Integer>();
        for (var items : byShop.values())
            for (var item : items) {
                // 仅替换严格更低的价格；同价保留普通价，活动之间会员价优先。
                var best = prices.stream().filter(p -> p.getSkuId().equals(item.line.skuId()) && p.getPrice() != null
                                && eligible(p.getEligibleLevelCodes(), level) && p.getPrice().compareTo(item.unitPrice) < 0)
                        .min(Comparator.comparing(PricingScope::getPrice)
                                .thenComparingInt(p -> p.getActivityType().equals("MEMBER_PRICE") ? 0 : 1)
                                .thenComparing(PricingScope::getActivityId)).orElse(null);
                if (best == null)
                    continue;
                if (best.getPerUserLimit() != null) {
                    limitedQuantities.merge(best.getActivityCode(), (long) item.line.quantity(), Long::sum);
                    purchaseLimits.merge(best.getActivityCode(), best.getPerUserLimit(), Integer::min);
                }
                if (best.getSalesLimit() != null &&
                        (long) best.getReservedQty() + best.getPaidQty() + item.line.quantity() > best.getSalesLimit())
                    conflict("PURCHASE_LIMIT_REACHED", "活动销售上限不足");
                item.unitPrice = best.getPrice();
                item.payable = item.unitPrice.multiply(BigDecimal.valueOf(item.line.quantity()));
                item.activitySku = best.getActivitySkuId();
                item.discounts.add(new Raw(best.getActivityId(),
                        best.getActivityType().equals("MEMBER_PRICE") ? "MEMBER" : "LIMITED", null, null,
                        item.original().subtract(item.payable),
                        json.writeValueAsString(Map.of("activitySkuId", item.activitySku,
                                "quantity", item.line.quantity(), "ordinaryUnitPrice", item.line.ordinaryPrice(),
                                "unitPrice", item.unitPrice))));
            }
        for (var entry : limitedQuantities.entrySet()) {
            Integer used = mapper.usedQuota(entry.getKey(), user, lock);
            if ((used == null ? 0L : used.longValue()) + entry.getValue() > purchaseLimits.get(entry.getKey()))
                conflict("PURCHASE_LIMIT_REACHED", "已达到活动累计限购");
        }
        if (lock)
            coupons = selectedCoupons.isEmpty() ? List.of() : mapper.available(user, now, true, selectedCoupons);
        var funds = activityIds.isEmpty() ? Map.<Long, List<MarketingBudget>>of() : mapper.budgets(
                        new ArrayList<>(activityIds), lock).stream()
                .collect(Collectors.groupingBy(MarketingBudget::activityId));
        var shopViews = new ArrayList<QuoteVO.Shop>();
        var candidates = new ArrayList<QuoteVO.CouponCandidate>();
        var usedCoupons = new HashSet<Long>();
        var allocations = new ArrayList<Allocation>();
        BigDecimal original = ZERO, payable = ZERO, reward = ZERO;
        boolean ownPurchase = false;
        for (var entry : byShop.entrySet()) {
            long shop = entry.getKey();
            var items = entry.getValue();
            var choice = selections.getOrDefault(shop, new ShopChoice(shop, "NONE", null, null, null));
            if (choice.reductionActivityId() != null) {
                long id = choice.reductionActivityId();
                if (funds.getOrDefault(id, List.of()).stream().noneMatch(b -> Objects.equals(b.shopId(), shop)))
                    conflict("ACTIVITY_NOT_AVAILABLE", "满减范围不包含该店铺");
                var eligibleItems = items.stream().filter(i -> scopes.get(id).contains(i.line.skuId())).toList();
                var subtotal = eligibleItems.stream().map(i -> i.payable).reduce(ZERO, BigDecimal::add);
                var tier = details.tiers(id).stream().filter(t -> t.thresholdAmount().compareTo(subtotal) <= 0)
                        .max(Comparator.comparing(ActivityPublicVO.Tier::thresholdAmount)).orElse(null);
                if (tier != null)
                    apply(eligibleItems, id, "FULL_REDUCTION", null, tier.id(), tier.reductionAmount().min(subtotal),
                            json.writeValueAsString(Map.of("thresholdAmount", tier.thresholdAmount(), "reductionAmount",
                                    tier.reductionAmount())));
            }
            var usable = new ArrayList<UserCoupon>();
            var savings = new HashMap<Long, BigDecimal>();
            for (var coupon : coupons) {
                if (funds.getOrDefault(coupon.getActivityId(), List.of()).stream()
                        .noneMatch(b -> Objects.equals(b.shopId(), shop)))
                    continue;
                var subtotal = items.stream().filter(i -> scopes.get(coupon.getActivityId()).contains(i.line.skuId()))
                        .map(i -> i.payable).reduce(ZERO, BigDecimal::add);
                boolean valid =
                        !usedCoupons.contains(coupon.getId()) && subtotal.compareTo(coupon.getThresholdAmount()) >= 0;
                var saving = valid ? coupon.getFaceAmount().min(subtotal) : ZERO;
                candidates.add(
                        new QuoteVO.CouponCandidate(coupon.getId(), shop, valid, valid ? null : "COUPON_NOT_USABLE",
                                saving));
                if (valid) {
                    usable.add(coupon);
                    savings.put(coupon.getId(), saving);
                }
            }
            UserCoupon chosen = null;
            if (choice.couponMode().equals("SELECT")) {
                chosen = usable.stream().filter(c -> c.getId().equals(choice.userCouponId())).findFirst().orElse(null);
                if (chosen == null)
                    conflict("COUPON_NOT_USABLE", "指定优惠券当前不可用");
            } else if (choice.couponMode().equals("AUTO")) {
                // 推荐复用候选展示的实际优惠额，金额相同再按到期日和券 ID 决胜。
                chosen = usable.stream().min(Comparator.comparing((UserCoupon c) -> savings.get(c.getId())).reversed()
                        .thenComparing(UserCoupon::getExpiresAt).thenComparing(UserCoupon::getId)).orElse(null);
            }
            if (chosen != null) {
                var coupon = chosen;
                var eligibleItems = items.stream()
                        .filter(i -> scopes.get(coupon.getActivityId()).contains(i.line.skuId())).toList();
                apply(eligibleItems, coupon.getActivityId(), "COUPON", coupon.getId(), null, coupon.getFaceAmount(),
                        json.writeValueAsString(Map.of("faceAmount", coupon.getFaceAmount(), "thresholdAmount",
                                coupon.getThresholdAmount())));
                usedCoupons.add(coupon.getId());
            }
            var shopOriginal = items.stream().map(Item::original).reduce(ZERO, BigDecimal::add);
            var shopPayable = items.stream().map(i -> i.payable).reduce(ZERO, BigDecimal::add);
            original = original.add(shopOriginal);
            payable = payable.add(shopPayable);
            var groupedDiscounts = new LinkedHashMap<Long, List<ItemDiscount>>();
            for (var item : items) {
                if (user.equals(item.line.ownerUserId()))
                    ownPurchase = true;
                else
                    reward = reward.add(item.payable);
                for (var discount : item.discounts) {
                    groupedDiscounts.computeIfAbsent(discount.activity(), ignored -> new ArrayList<>())
                            .add(new ItemDiscount(item.line.skuId(), discount));
                }
            }
            for (var group : groupedDiscounts.entrySet()) {
                int bp = funds.getOrDefault(group.getKey(), List.of()).stream()
                        .filter(b -> Objects.equals(b.shopId(), shop)).findFirst()
                        .orElseThrow(() -> new BusinessException(409, "活动预算当前不可用",
                                Map.of("reason", "ACTIVITY_NOT_AVAILABLE"), null)).platformShareBp();
                var amounts = group.getValue().stream().map(line -> line.benefit().amount()).toList();
                // 同店同活动先汇总承担再分回明细，避免逐行取整突破发券时冻结的预算。
                var platform = PromotionAmounts.platformShare(amounts.stream().reduce(ZERO, BigDecimal::add), bp);
                var shares = PromotionAmounts.allocate(platform, amounts);
                for (int i = 0; i < shares.size(); i++) {
                    var line = group.getValue().get(i);
                    var discount = line.benefit();
                    allocations.add(
                            new Allocation(line.sku(), shop, discount.activity(), discount.kind(), discount.coupon(),
                                    discount.tier(), discount.amount(), shares.get(i), bp, discount.snapshot()));
                }
            }
            shopViews.add(new QuoteVO.Shop(shop, shopOriginal, shopOriginal.subtract(shopPayable), shopPayable, 0,
                    chosen == null ? null : chosen.getId(), choice.reductionActivityId(), null,
                    items.stream().map(Item::view).toList()));
        }
        if (original.compareTo(MAX_AMOUNT) > 0)
            throw new BusinessException(400, "报价总金额超过金额上限", Map.of("reason", "INVALID_ARGUMENT"), null);
        // 店主与地址也参与摘要，防止转店或修改地址后仍以旧报价确认权益。
        String quoteHash = HexFormat.of()
                .formatHex(hash.hash(Map.of("userId", user, "addressHash", addressHash, "level", level,
                        "shops", shopViews, "owners",
                        lines.stream().sorted(Comparator.comparing(Line::shopId).thenComparing(Line::skuId))
                                .map(Line::ownerUserId).toList(), "businessType", "NORMAL")));
        var quote = new QuoteVO(quoteHash, now.atOffset(ZoneOffset.ofHours(8)), "NORMAL", level, original,
                original.subtract(payable), payable, 0,
                shopViews, candidates, new QuoteVO.RewardPreview(reward.movePointRight(2).longValueExact(), reward,
                ownPurchase ? "SELF_SHOP_OWNER" : null));
        return new Price(quote, allocations);
    }

    /** 各层优惠按当时剩余应付分摊，后续满减／用券不会重新使用已扣除的金额。 */
    private void apply(List<Item> items, Long activity, String kind, Long coupon, Long tier, BigDecimal amount,
                       String snapshot) {
        var portions = PromotionAmounts.allocate(amount, items.stream().map(i -> i.payable).toList());
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var portion = portions.get(i);
            if (portion.signum() == 0)
                continue;
            item.payable = item.payable.subtract(portion);
            item.discounts.add(new Raw(activity, kind, coupon, tier, portion, snapshot));
        }
    }
}
