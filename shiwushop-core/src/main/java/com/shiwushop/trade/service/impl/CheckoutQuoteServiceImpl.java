package com.shiwushop.trade.service.impl;

import com.shiwushop.user.entity.address.Address;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.service.MarketingPricingService;
import com.shiwushop.marketing.vo.QuoteVO;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.trade.dto.cart.QuoteRequest;
import com.shiwushop.trade.mapper.cart.CartItemMapper;
import com.shiwushop.trade.service.CheckoutQuoteService;
import com.shiwushop.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CheckoutQuoteServiceImpl implements CheckoutQuoteService {
    private final AccountSecurity security;
    private final AddressService addresses;
    private final CartItemMapper carts;
    private final TradingProductService products;
    private final ShopService shops;
    private final MemberAccountService members;
    private final MarketingPricingService pricing;
    private final MarketingSettings settings;
    private final ActivityContentHash hash;

    private static void invalid(String message) {
        throw new BusinessException(400, message, Map.of("reason", "INVALID_ARGUMENT"), null);
    }

    /** 比较地址密文快照即可识别报价后变更，无需为摘要额外解密个人信息。 */
    static String addressHash(ActivityContentHash hash, Address address) {
        return HexFormat.of().formatHex(hash.hash(Map.of("id", address.getId(), "keyVersion", address.getKeyVersion(),
                "consignee", Base64.getEncoder().encodeToString(address.getConsigneeCiphertext()), "phone",
                Base64.getEncoder().encodeToString(address.getPhoneCiphertext()), "province", address.getProvince(),
                "city", address.getCity(), "district", address.getDistrict(), "detail",
                Base64.getEncoder().encodeToString(address.getDetailCiphertext()))));
    }

    /** 报价只预览、不占资源；提交订单仍需在锁内复算并核对报价哈希。 */
    @Override
    @Transactional(readOnly = true)
    public QuoteVO quote(Long user, QuoteRequest request) {
        security.checkActive(user);
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
        var quantities = new TreeMap<Long, Integer>();
        if (request.source().equals("DIRECT")) {
            if (request.skuId() == null || request.quantity() == null || request.cartItemIds() != null)
                invalid("直接报价参数组合不正确");
            quantities.put(request.skuId(), request.quantity());
        } else {
            if (request.cartItemIds() == null || request.skuId() != null || request.quantity() != null ||
                    !request.businessType().equals("NORMAL"))
                invalid("购物车报价参数组合不正确");
            if (new HashSet<>(request.cartItemIds()).size() != request.cartItemIds().size())
                invalid("购物车项不能重复");
            for (long id : request.cartItemIds()) {
                var item = carts.selectById(id);
                if (item == null || !user.equals(item.getUserId()))
                    throw new BusinessException(404, "购物车商品不存在");
                quantities.put(item.getSkuId(), item.getQuantity());
            }
        }
        if (!request.businessType().equals("NORMAL"))
            throw new BusinessException(409, "该专属活动购买链路尚未启用", Map.of("reason", "ACTIVITY_NOT_AVAILABLE"),
                    null);
        if (request.activitySkuId() != null || request.groupId() != null || request.bargainId() != null)
            invalid("普通报价不能指定专属活动资格");
        var address = addresses.forCheckout(user, request.addressId());
        String addressHash = addressHash(hash, address);
        var catalog = products.cartProducts(quantities);
        var owners = shops.findByIds(
                        catalog.values().stream().filter(p -> p.product() != null).map(p -> p.product().getShopId()).distinct()
                                .toList())
                .stream().collect(Collectors.toMap(s -> s.getId(), s -> s.getOwnerUserId()));
        var lines = new ArrayList<MarketingPricingService.Line>();
        for (var entry : quantities.entrySet()) {
            var item = catalog.get(entry.getKey());
            if (item == null || item.sku() == null)
                throw new BusinessException(404, "商品SKU不存在");
            if (item.unavailableReason() != null)
                throw new BusinessException(409, item.unavailableReason(),
                        Map.of("reason", item.unavailableReason()
                                .equals("库存不足") ? "STOCK_NOT_ENOUGH" : "ACTIVITY_NOT_AVAILABLE"), null);
            long shop = item.product().getShopId();
            lines.add(new MarketingPricingService.Line(entry.getKey(), shop, owners.get(shop), entry.getValue(),
                    item.sku().getPrice()));
        }
        return pricing.price(user, members.overview(user).levelCode(), lines, request.shopChoices(), addressHash, false)
                .quote();
    }
}
