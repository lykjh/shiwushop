package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingParticipationQueryMapper;
import com.shiwushop.marketing.service.MarketingParticipationQueryService;
import com.shiwushop.marketing.vo.ParticipationPage;
import com.shiwushop.marketing.vo.PointRedemptionPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class MarketingParticipationQueryServiceImpl implements MarketingParticipationQueryService {
    private final MarketingParticipationQueryMapper mapper;
    private final MarketingSettings settings;

    @Override
    @Transactional(readOnly = true)
    public ParticipationPage participations(long user, Long afterId, int size, String type, String status) {
        enabled();
        var rows = mapper.participations(user, type, status, afterId, size + 1);
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(p -> new ParticipationPage.Participation(p.id(), p.type(),
                p.activityId(), p.activitySkuId(), p.orderId(), p.groupId(), p.bargainId(), p.quantity(),
                p.status(), p.expiresAt().atOffset(ZoneOffset.ofHours(8)),
                p.paidAt() == null ? null : p.paidAt().atOffset(ZoneOffset.ofHours(8)))).toList();
        return new ParticipationPage(items, more, more ? items.getLast().id() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public PointRedemptionPage redemptions(long user, Long afterId, int size, String status) {
        enabled();
        var rows = mapper.redemptions(user, status, afterId, size + 1);
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(r -> new PointRedemptionPage.PointRedemption(r.orderId(),
                r.participationId(), r.activitySkuId(), r.spentPointUnits(), r.returnedPointUnits(), r.status(),
                r.createdAt().atOffset(ZoneOffset.ofHours(8)))).toList();
        return new PointRedemptionPage(items, more, more ? items.getLast().orderId() : null);
    }

    private void enabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }
}
