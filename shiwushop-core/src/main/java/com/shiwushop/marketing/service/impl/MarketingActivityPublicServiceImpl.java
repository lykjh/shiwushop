package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import com.shiwushop.marketing.mapper.MarketingActivityPublicMapper;
import com.shiwushop.marketing.model.*;
import com.shiwushop.marketing.service.MarketingActivityPublicService;
import com.shiwushop.marketing.vo.ActivityPublicVO;
import com.shiwushop.product.service.TradingProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketingActivityPublicServiceImpl implements MarketingActivityPublicService {
    private final MarketingActivityPublicMapper mapper;
    private final MarketingActivityMapper activities;
    private final TradingProductService products;
    private final MarketingSettings settings;
    private final ObjectMapper json;
    private final Clock businessClock;

    private void enabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private ActivityPublicVO.Card card(MarketingActivity row) {
        return new ActivityPublicVO.Card(row.getId(), row.getActivityCode(), row.getVersionNo(), row.getName(),
                row.getActivityType(),
                row.getStatus(), row.getStartAt().atOffset(ZoneOffset.ofHours(8)),
                row.getEndAt().atOffset(ZoneOffset.ofHours(8)),
                Arrays.asList(json.readValue(row.getEligibleLevelCodes(), String[].class)), row.getRulesText());
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityPublicVO.Page list(Long afterId, int size, String type, Long shop, Long sku) {
        enabled();
        var rows = mapper.list(afterId, size + 1, type, shop, sku, LocalDateTime.now(businessClock));
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(this::card).toList();
        return new ActivityPublicVO.Page(items, more, more ? items.getLast().id() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityPublicVO detail(Long id) {
        enabled();
        var row = activities.find(id);
        if (row == null || !Set.of("APPROVED", "RUNNING", "PAUSED", "ENDED", "TERMINATED").contains(row.getStatus()))
            throw new BusinessException(404, "活动不存在");
        var card = card(row);
        var scopes = mapper.scopes(id);
        var catalog = products.cartProducts(
                scopes.stream().collect(Collectors.toMap(ActivityScope::getSkuId, s -> 1, (a, b) -> a)));
        var visible = scopes.stream().map(s -> {
            var draft = s.draft();
            return new ActivityPublicVO.Scope(s.getId(), s.getSkuId(), catalog.get(s.getSkuId()).product().getShopId(),
                    s.getScopeRole(),
                    draft.memberPrices(), s.getActivityPrice(), s.getPerUserLimit(), s.getSalesLimit(), s.getTeamSize(),
                    s.getParticipationSeconds(),
                    s.getBargainStartPrice(), s.getBargainTargetPrice(), s.getRequiredHelpers(), s.getDailyHelpLimit(),
                    s.getMaxOpenSessions(), s.getPointCostUnits());
        }).toList();
        var rule = mapper.coupon(id);
        ActivityPublicVO.CouponRule coupon =
                rule == null ? null : new ActivityPublicVO.CouponRule(rule.faceAmount(), rule.thresholdAmount(),
                        rule.perUserLimit(), rule.claimEndAt().atOffset(ZoneOffset.ofHours(8)), rule.validityDays(),
                        rule.fixedExpireAt() == null ? null : rule.fixedExpireAt().atOffset(ZoneOffset.ofHours(8)),
                        rule.grantMode(), rule.memberLevelCode());
        return new ActivityPublicVO(card.id(), card.activityCode(), card.versionNo(), card.name(), card.type(),
                card.status(), card.startAt(), card.endAt(),
                card.eligibleLevelCodes(), card.rulesText(), visible, mapper.tiers(id), coupon);
    }
}
