package com.shiwushop.marketing.service.impl;

import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainExpiryMapper;
import com.shiwushop.marketing.mapper.MarketingBargainHelpMapper;
import com.shiwushop.marketing.service.MarketingBargainExpiryService;
import com.shiwushop.product.service.TradingProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 未转订单的砍价资格到期时归还原活动库存，保留历史参与与助力事实。 */
@Service
@RequiredArgsConstructor
public class MarketingBargainExpiryServiceImpl implements MarketingBargainExpiryService {
    private final MarketingBargainExpiryMapper mapper;
    private final MarketingBargainHelpMapper bargains;
    private final TradingProductService products;
    private final MarketingStockLedger stock;
    private final MarketingSettings settings;
    private final Clock businessClock;

    @Override
    public List<Long> due() {
        return settings.enabled() ? mapper.due(now()) : List.of();
    }

    @Override
    @Transactional
    public void expire(long bargainId) {
        var root = bargains.lock(bargainId);
        if (root == null)
            return;
        var now = now();
        boolean activeDue = "ACTIVE".equals(root.status()) && !now.isBefore(root.helpExpiresAt());
        boolean qualifiedDue = "QUALIFIED".equals(root.status()) && root.purchaseExpiresAt() != null
                && !now.isBefore(root.purchaseExpiresAt());
        if (!activeDue && !qualifiedDue)
            return;
        var participation = mapper.lockParticipation(root.participationId());
        if (participation == null || participation.orderId() != null)
            return;
        String expected = activeDue ? "RESERVED" : "QUALIFIED";
        if (!expected.equals(participation.status()))
            return;
        var scope = mapper.scope(root.activitySkuId());
        products.lockSku(scope.skuId());
        stock.release(root.activitySkuId(), root.participationId(), 1,
                "BARGAIN:EXPIRE:" + root.participationId());
        if (mapper.releaseOpenQuota(scope.activityCode(), root.userId()) != 1
                || mapper.releaseParticipation(root.participationId(), expected) != 1
                || mapper.expire(bargainId, root.status()) != 1)
            throw new IllegalStateException("砍价到期释放账本不一致");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
    }
}
