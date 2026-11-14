package com.shiwushop.marketing.service.impl;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingGroupMapper;
import com.shiwushop.marketing.mapper.MarketingOrderSourceMapper;
import com.shiwushop.marketing.service.MarketingGroupExpiryService;
import com.shiwushop.merchant.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketingGroupExpiryServiceImpl implements MarketingGroupExpiryService {
    private final MarketingGroupMapper groups;
    private final MarketingOrderSourceMapper sources;
    private final ShopService shops;
    private final MarketingSettings settings;
    private final Clock clock;

    @Override
    public List<Long> due() {
        return settings.enabled() ? groups.due(now()) : List.of();
    }

    @Override
    @Transactional
    public void expire(long groupId) {
        var shopId = groups.shopId(groupId);
        if (shopId == null) return;
        shops.lockById(shopId);
        var group = sources.lockGroup(groupId);
        var now = now();
        if (group != null && "FORMING".equals(group.status()) && !group.expiresAt().isAfter(now))
            groups.fail(groupId, now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }
}
