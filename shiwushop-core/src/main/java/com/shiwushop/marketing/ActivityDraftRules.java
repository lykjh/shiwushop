package com.shiwushop.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.dto.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Bean Validation处理字段形状；这里仅处理类型之间的组合约束。 */
public final class ActivityDraftRules {
    private ActivityDraftRules() {
    }

    public static LocalDateTime time(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }

    public static void validate(ActivityDraft draft) {
        if (!time(draft.endAt()).isAfter(time(draft.startAt())))
            invalid("结束时间必须晚于开始时间（毫秒精度）");
        if (new HashSet<>(draft.eligibleLevelCodes()).size() != draft.eligibleLevelCodes().size())
            invalid("适用等级不能重复");
        var identities = new HashSet<String>();
        for (var scope : draft.scopes()) {
            if (!identities.add(scope.skuId() + ":" + scope.scopeRole()))
                invalid("商品范围不能重复");
            validateScope(draft.type(), scope);
        }
        if (draft.scopes().stream()
                .noneMatch(s -> (draft.type().equals("POINTS") ? "REDEEM" : "SALE").equals(s.scopeRole())))
            invalid("活动缺少主商品范围");
        boolean tiered = Set.of("FULL_REDUCTION", "FULL_GIFT").contains(draft.type());
        if (tiered == draft.tiers().isEmpty())
            invalid("只有满减或满赠应配置非空阶梯");
        var thresholds = new HashSet<java.math.BigDecimal>();
        for (var tier : draft.tiers()) {
            if (!thresholds.add(tier.thresholdAmount().stripTrailingZeros()))
                invalid("阶梯门槛不能重复");
            if (draft.type().equals("FULL_REDUCTION")) {
                if (tier.reductionAmount() == null || tier.giftSkuId() != null || tier.giftQuantity() != null
                        || tier.reductionAmount().compareTo(tier.thresholdAmount()) > 0)
                    invalid("满减阶梯只接受不超过门槛的减额");
            } else {
                if (tier.reductionAmount() != null || tier.giftSkuId() == null || tier.giftQuantity() == null
                        || draft.scopes().stream()
                        .noneMatch(s -> s.skuId().equals(tier.giftSkuId()) && s.scopeRole().equals("GIFT")))
                    invalid("满赠阶梯必须引用本活动赠品范围");
            }
        }
        if (draft.type().equals("COUPON") != (draft.couponRule() != null))
            invalid("仅券活动必须配置券规则");
        if (draft.couponRule() != null) {
            var rule = draft.couponRule();
            if (rule.thresholdAmount().compareTo(rule.faceAmount()) < 0)
                invalid("券门槛不能小于面额");
            if (time(rule.claimEndAt()).isAfter(time(draft.endAt())))
                invalid("领券截止不能晚于活动结束");
            if (!time(rule.claimEndAt()).isAfter(time(draft.startAt())))
                invalid("领券截止必须晚于活动开始");
            if (rule.grantMode().equals("MEMBERSHIP")) {
                if (rule.memberLevelCode() == null || rule.memberLevelCode().equals("ORDINARY") ||
                        rule.validityDays() != 30)
                    invalid("月券必须指定有权益等级并保持30天有效期");
            } else if (rule.memberLevelCode() != null)
                invalid("非月券不能指定月权益等级");
        }
        var parties = new HashSet<Long>();
        for (var budget : draft.budgetProposals()) {
            long party;
            if (budget.party().equals("PLATFORM")) {
                if (budget.shopId() != null || budget.platformShareBp() != null)
                    invalid("平台预算不指定店铺或分摊比例");
                party = 0;
            } else {
                if (budget.shopId() == null || budget.platformShareBp() == null)
                    invalid("商家预算必须指定店铺和平台比例");
                party = budget.shopId();
            }
            if (!parties.add(party))
                invalid("同一承担方不能重复配置预算");
        }
    }

    private static void validateScope(String type, ScopeDraft scope) {
        String role = scope.scopeRole();
        Set<String> roles = switch (type) {
            case "POINTS" -> Set.of("REDEEM");
            case "FULL_GIFT" -> Set.of("SALE", "GIFT");
            default -> Set.of("SALE");
        };
        if (!roles.contains(role))
            invalid("商品范围身份不符合活动类型");
        Map<String, Object> fields = new HashMap<>();
        fields.put("memberPrices", scope.memberPrices());
        fields.put("activityPrice", scope.activityPrice());
        fields.put("perUserLimit", scope.perUserLimit());
        fields.put("salesLimit", scope.salesLimit());
        fields.put("teamSize", scope.teamSize());
        fields.put("participationSeconds", scope.participationSeconds());
        fields.put("bargainStartPrice", scope.bargainStartPrice());
        fields.put("bargainTargetPrice", scope.bargainTargetPrice());
        fields.put("requiredHelpers", scope.requiredHelpers());
        fields.put("dailyHelpLimit", scope.dailyHelpLimit());
        fields.put("maxOpenSessions", scope.maxOpenSessions());
        fields.put("pointCostUnits", scope.pointCostUnits());
        fields.put("unitCostAmount", scope.unitCostAmount());
        fields.values().removeIf(Objects::isNull);
        Set<String> required = switch (type) {
            case "MEMBER_PRICE" -> Set.of("memberPrices");
            case "LIMITED_PRICE" -> Set.of("activityPrice");
            case "FLASH" -> Set.of("activityPrice", "perUserLimit");
            case "GROUP" -> Set.of("activityPrice", "teamSize", "participationSeconds");
            case "BARGAIN" ->
                    Set.of("bargainStartPrice", "bargainTargetPrice", "requiredHelpers", "participationSeconds",
                            "dailyHelpLimit", "maxOpenSessions");
            case "POINTS" -> Set.of("pointCostUnits", "perUserLimit", "unitCostAmount");
            case "FULL_GIFT" -> role.equals("GIFT") ? Set.of("unitCostAmount") : Set.of();
            default -> Set.of();
        };
        Set<String> allowed = type.equals("LIMITED_PRICE") ? Set.of("activityPrice", "perUserLimit",
                "salesLimit") : required;
        if (!fields.keySet().containsAll(required) || !allowed.containsAll(fields.keySet()))
            invalid("商品范围存在缺失或不适用的活动参数");
        if (type.equals("MEMBER_PRICE")) {
            var prices = scope.memberPrices();
            if (prices.silver().compareTo(prices.gold()) < 0 || prices.gold().compareTo(prices.platinum()) < 0)
                invalid("会员价格须满足白银不低于黄金、黄金不低于白金");
        }
        if (type.equals("BARGAIN") && scope.bargainStartPrice().subtract(scope.bargainTargetPrice())
                .movePointRight(2).compareTo(java.math.BigDecimal.valueOf(scope.requiredHelpers())) < 0)
            invalid("砍价价差分数必须不少于助力人数");
        if (type.equals("POINTS") && scope.pointCostUnits() % 100 != 0)
            invalid("兑换价格必须为整积分");
    }

    private static void invalid(String message) {
        throw new BusinessException(400, message, Map.of("reason", "INVALID_ARGUMENT"), null);
    }
}
