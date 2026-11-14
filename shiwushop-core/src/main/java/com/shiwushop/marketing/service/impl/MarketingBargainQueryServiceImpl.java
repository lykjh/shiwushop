package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainQueryMapper;
import com.shiwushop.marketing.service.MarketingBargainQueryService;
import com.shiwushop.marketing.vo.BargainPublicVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class MarketingBargainQueryServiceImpl implements MarketingBargainQueryService {
    private final MarketingBargainQueryMapper mapper;
    private final MarketingSettings settings;
    private final Clock businessClock;

    @Override
    @Transactional(readOnly = true)
    public BargainPublicVO detail(long bargainId) {
        enabled();
        var row = mapper.find(bargainId);
        if (row == null)
            throw new BusinessException(404, "砍价单不存在");
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public BargainPublicVO.Mine mineDetail(long user, long bargainId) {
        enabled();
        var row = mapper.findMine(bargainId, user);
        if (row == null)
            throw new BusinessException(404, "砍价单不存在");
        return ownView(row, LocalDateTime.now(businessClock));
    }

    @Override
    @Transactional(readOnly = true)
    public BargainPublicVO.Page mine(long user, Long afterId, int size, String status) {
        enabled();
        var rows = mapper.mine(user, status, afterId, size + 1);
        boolean more = rows.size() > size;
        var now = LocalDateTime.now(businessClock);
        var items = rows.stream().limit(size).map(row -> ownView(row, now)).toList();
        return new BargainPublicVO.Page(items, more, more ? items.getLast().id() : null);
    }

    private static BargainPublicVO.Mine ownView(MarketingBargainQueryMapper.BargainRow row, LocalDateTime now) {
        return new BargainPublicVO.Mine(row.id(), row.activityId(),
                row.activitySkuId(), row.skuId(), row.status(), row.startAmount(), row.targetAmount(),
                row.currentAmount(), row.requiredHelpers(), row.helpCount(),
                row.helpExpiresAt().atOffset(ZoneOffset.ofHours(8)),
                row.purchaseExpiresAt() == null ? null : row.purchaseExpiresAt().atOffset(ZoneOffset.ofHours(8)),
                row.participationId(), row.orderId(), "QUALIFIED".equals(row.status())
                && row.orderId() == null
                && row.purchaseExpiresAt() != null && now.isBefore(row.purchaseExpiresAt())
                && "ACTIVE".equals(row.skuStatus()) && "ON_SALE".equals(row.productStatus())
                && row.shopEnabled());
    }

    private void enabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private static BargainPublicVO view(MarketingBargainQueryMapper.BargainRow row) {
        return new BargainPublicVO(row.id(), row.activityId(), row.activitySkuId(), row.skuId(), row.status(),
                row.startAmount(), row.targetAmount(), row.currentAmount(), row.requiredHelpers(), row.helpCount(),
                row.helpExpiresAt().atOffset(ZoneOffset.ofHours(8)),
                row.purchaseExpiresAt() == null ? null : row.purchaseExpiresAt().atOffset(ZoneOffset.ofHours(8)));
    }
}
