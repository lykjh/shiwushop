package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingBargainStartMapper;
import com.shiwushop.marketing.service.MarketingBargainQueryService;
import com.shiwushop.marketing.service.MarketingBargainStartService;
import com.shiwushop.marketing.vo.BargainPublicVO;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.user.service.AccountTradeGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 发起砍价时冻结活动版本、占一份专用库存和跨版本账号资格。 */
@Service
@RequiredArgsConstructor
public class MarketingBargainStartServiceImpl implements MarketingBargainStartService {
    private final MarketingBargainStartMapper mapper;
    private final MarketingStockLedger stock;
    private final MarketingBargainQueryService queries;
    private final AccountSecurity security;
    private final AccountTradeGate tradeGate;
    private final MemberAccountService members;
    private final ShopService shops;
    private final TradingProductService products;
    private final MarketingSettings settings;
    private final ActivityContentHash hash;
    private final ObjectMapper json;
    private final Clock businessClock;

    @Override
    @Transactional
    public BargainPublicVO.Mine start(Long user, long activitySkuId, String requestKey) {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
        tradeGate.requireCanStartTrade(user);
        security.checkActive(user);
        var skuId = mapper.skuId(activitySkuId);
        var initialSku = skuId == null ? null : products.findSku(skuId);
        var initialProduct = initialSku == null ? null : products.findProduct(initialSku.getProductId());
        if (initialProduct == null)
            throw new BusinessException(404, "砍价活动商品不存在");
        var shop = shops.lockById(initialProduct.getShopId());
        if (shop == null)
            throw new BusinessException(404, "砍价活动商品不存在");
        var account = members.initializeAndLock(user);
        var sku = products.lockSku(skuId);
        var product = sku == null ? null : products.findProduct(sku.getProductId());
        if (product == null || !product.getShopId().equals(shop.getId()))
            throw new BusinessException(404, "砍价活动商品不存在");
        var scope = mapper.lockScope(activitySkuId);
        if (scope == null || !scope.skuId().equals(skuId))
            throw new BusinessException(404, "砍价活动商品不存在");
        byte[] requestHash = hash.hash(List.of("BARGAIN_START", activitySkuId));
        var previous = mapper.participation(user, requestKey);
        if (previous != null) {
            if (!previous.activitySkuId().equals(activitySkuId)
                    || !Arrays.equals(previous.requestHash(), requestHash))
                throw conflict("IDEMPOTENCY_CONFLICT", "发起请求键对应不同活动商品");
            var bargainId = mapper.bargainId(previous.id());
            if (bargainId == null)
                throw conflict("IDEMPOTENCY_CONFLICT", "发起请求键已用于其他参与");
            return queries.mineDetail(user, bargainId);
        }
        var now = LocalDateTime.now(businessClock).truncatedTo(ChronoUnit.MILLIS);
        if (!shop.isTradingEnabled() || !"ACTIVE".equals(sku.getStatus())
                || product.getStatus() != ProductStatus.ON_SALE)
            throw conflict("PRODUCT_UNAVAILABLE", "活动商品不可购买");
        if (!"RUNNING".equals(scope.status()) || now.isBefore(scope.startAt()) || !now.isBefore(scope.endAt()))
            throw conflict("ACTIVITY_NOT_AVAILABLE", "砍价活动不在有效时间内");
        if (!Arrays.asList(json.readValue(scope.eligibleLevelCodes(), String[].class)).contains(account.getLevelCode()))
            throw conflict("MEMBER_NOT_ELIGIBLE", "当前等级不能参与该活动");
        if (mapper.openCount(activitySkuId) >= scope.maxOpenSessions())
            throw conflict("BARGAIN_LIMIT_REACHED", "当前砍价单数量已满");

        var expires = now.plusSeconds(scope.participationSeconds());
        if (expires.isAfter(scope.endAt()))
            expires = scope.endAt();
        mapper.insertParticipation(activitySkuId, user, requestKey, requestHash, expires);
        long participation = mapper.lastId();
        stock.reserve(activitySkuId, participation, 1, "BARGAIN:START:" + participation);
        mapper.ensureOpenQuota(scope.activityId(), scope.activityCode(), user);
        if (mapper.reserveOpenQuota(scope.activityCode(), user) != 1)
            throw conflict("BARGAIN_LIMIT_REACHED", "该活动已有进行中的砍价单");
        mapper.insertBargain(participation, activitySkuId, user, scope.bargainStartPrice(),
                scope.bargainTargetPrice(), scope.requiredHelpers(), expires);
        return queries.mineDetail(user, mapper.lastId());
    }

    private static BusinessException conflict(String reason, String message) {
        return new BusinessException(409, message, Map.of("reason", reason), null);
    }
}
