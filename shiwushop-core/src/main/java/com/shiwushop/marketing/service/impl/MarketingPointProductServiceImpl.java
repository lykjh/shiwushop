package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingPointProductMapper;
import com.shiwushop.marketing.service.MarketingPointProductService;
import com.shiwushop.marketing.vo.PointProductPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class MarketingPointProductServiceImpl implements MarketingPointProductService {
    private final MarketingPointProductMapper mapper;
    private final MarketingSettings settings;
    private final Clock businessClock;

    @Override
    @Transactional(readOnly = true)
    public PointProductPage available(Long shopId, Long afterId, int size) {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
        var rows = mapper.available(shopId, afterId, size + 1, LocalDateTime.now(businessClock));
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(r -> new PointProductPage.PointProduct(r.activityId(),
                r.activitySkuId(), r.skuId(), r.productName(), r.coverMediaId(), r.shopId(), r.pointCostUnits(),
                r.perUserLimit(), r.availableQuantity(), r.startAt().atOffset(ZoneOffset.ofHours(8)),
                r.endAt().atOffset(ZoneOffset.ofHours(8)))).toList();
        return new PointProductPage(items, more, more ? items.getLast().activitySkuId() : null);
    }
}
