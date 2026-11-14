package com.shiwushop.trade.service.impl;

import com.shiwushop.marketing.ActivityContentHash;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.vo.MarketingOrderSource;
import com.shiwushop.member.dto.RewardLine;

import java.util.stream.Collectors;

import com.shiwushop.user.service.AccountTradeGate;
import com.shiwushop.user.entity.address.Address;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.marketing.dto.ShopChoice;
import com.shiwushop.marketing.service.MarketingPricingService;
import com.shiwushop.marketing.vo.QuoteVO;
import com.shiwushop.trade.dto.cart.CheckoutDTO;
import com.shiwushop.trade.dto.cart.DirectCheckoutDTO;
import com.shiwushop.trade.entity.cart.CartItem;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.cart.CartItemMapper;
import com.shiwushop.trade.mapper.order.CheckoutRequestMapper;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.service.CheckoutService;
import com.shiwushop.trade.vo.order.CheckoutVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import lombok.RequiredArgsConstructor;
import com.shiwushop.user.service.AddressService;
import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.member.service.MemberRewardService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** 结算、库存扣减与订单创建共用一个事务；购物车维护由 CartServiceImpl 负责。 */
@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {
    /** 下单业务错误附加数据中的原因码字段名。 */
    private static final String REASON = "reason";
    private final AccountTradeGate accountTradeGate;
    private final CartItemMapper cartItemMapper;
    private final CheckoutRequestMapper checkoutRequestMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final PrivacyCipher privacyCipher;
    private final Clock businessClock;
    private final AddressService addresses;
    private final TradingProductService products;
    private final ProductStockService stock;
    private final ShopService shops;
    private final MemberRewardService memberRewards;
    private final MarketingSettings marketing;
    private final ActivityContentHash contentHash;
    private final MarketingPricingService pricing;
    private final MarketingOrderSettlement settlement;

    private String promotionRequest(String quoteHash, List<ShopChoice> choices) {
        if (quoteHash == null && (choices == null || choices.isEmpty()))
            return "";
        if (quoteHash == null || choices != null && choices.stream().anyMatch(c -> "AUTO".equals(c.couponMode())))
            throw new BusinessException(400, "提交优惠选择必须确认报价并指定具体券");
        var sorted = choices == null ? List.of() : choices.stream().sorted(Comparator.comparing(ShopChoice::shopId))
                .toList();
        return "|" + quoteHash + "|" + HexFormat.of().formatHex(contentHash.hash(sorted));
    }

    /** 校验所选购物车项和幂等键，锁定归属条目后在一个事务内创建订单。 */
    @Override
    @Transactional
    public CheckoutVO checkoutSelected(Long userId, CheckoutDTO dto) {
        accountTradeGate.requireCanStartTrade(userId);
        if (new HashSet<>(dto.getCartItemIds()).size() != dto.getCartItemIds().size()) {
            throw new BusinessException(400, "购物车项不能重复");
        }
        String content = "CART|" + dto.getCartItemIds().stream().sorted().toList()
                + "|" + dto.getAddressId() + "|" + dto.getConfirmedTotal().stripTrailingZeros().toPlainString();
        String requestContent = content + promotionRequest(dto.getQuoteHash(), dto.getShopChoices());
        CheckoutVO existing = completedCheckoutIfPresent(userId, dto.getIdempotencyKey(), requestContent);
        if (existing != null)
            return existing;
        List<Long> skuIds = new ArrayList<>();
        for (Long id : dto.getCartItemIds()) {
            CartItem item = cartItemMapper.selectById(id);
            if (item == null || !userId.equals(item.getUserId()))
                throw new BusinessException(404, "购物车商品不存在");
            skuIds.add(item.getSkuId());
        }
        lockCheckoutShops(skuIds);
        Long requestId = beginCheckout(userId, dto.getIdempotencyKey(), requestContent);
        if (requestId < 0)
            return completedCheckout(-requestId);
        Address address = addresses.forCheckout(userId, dto.getAddressId());

        List<CartItem> selected = new ArrayList<>();
        for (Long id : dto.getCartItemIds().stream().sorted().toList()) {
            CartItem item = cartItemMapper.selectOwnedForUpdate(userId, id);
            if (item == null)
                throw new BusinessException(404, "购物车商品不存在");
            selected.add(item);
        }
        return placeOrders(userId, address, selected, dto.getConfirmedTotal(), requestId, dto.getQuoteHash(),
                dto.getShopChoices());
    }

    /** 将直接购买转换为临时结算项；复用订单、库存及幂等处理，不写购物车。 */
    @Override
    @Transactional
    public CheckoutVO checkoutDirect(Long userId, DirectCheckoutDTO dto) {
        accountTradeGate.requireCanStartTrade(userId);
        if (!"NORMAL".equals(dto.getBusinessType()))
            return checkoutActivity(userId, dto);
        if (dto.getActivitySkuId() != null || dto.getGroupId() != null || dto.getBargainId() != null ||
                dto.getConfirmedPointUnits() != null)
            throw new BusinessException(400, "普通下单不能指定专属活动或积分");
        String content = "DIRECT|" + dto.getSkuId() + "|" + dto.getQuantity()
                + "|" + dto.getAddressId() + "|" + dto.getConfirmedTotal().stripTrailingZeros().toPlainString();
        String requestContent = content + promotionRequest(dto.getQuoteHash(), dto.getShopChoices());
        CheckoutVO existing = completedCheckoutIfPresent(userId, dto.getIdempotencyKey(), requestContent);
        if (existing != null)
            return existing;
        lockCheckoutShops(List.of(dto.getSkuId()));
        Long requestId = beginCheckout(userId, dto.getIdempotencyKey(), requestContent);
        if (requestId < 0)
            return completedCheckout(-requestId);
        Address address = addresses.forCheckout(userId, dto.getAddressId());
        CartItem item = new CartItem();
        item.setSkuId(dto.getSkuId());
        item.setQuantity(dto.getQuantity());
        return placeOrders(userId, address, List.of(item), dto.getConfirmedTotal(), requestId, dto.getQuoteHash(),
                dto.getShopChoices());
    }

    private CheckoutVO checkoutActivity(Long userId, DirectCheckoutDTO dto) {
        if (!marketing.enabled())
            throw new BusinessException(409, "营销结算未启用");
        if (!Set.of("FLASH", "GROUP", "BARGAIN", "POINTS").contains(dto.getBusinessType())
                || dto.getActivitySkuId() == null || dto.getSkuId() == null || dto.getQuantity() == null
                || dto.getGroupId() != null && !"GROUP".equals(dto.getBusinessType())
                || dto.getBargainId() != null && !"BARGAIN".equals(dto.getBusinessType())
                || "BARGAIN".equals(dto.getBusinessType()) && dto.getBargainId() == null
                || dto.getConfirmedPointUnits() != null && !"POINTS".equals(dto.getBusinessType())
                || "POINTS".equals(dto.getBusinessType()) && dto.getConfirmedPointUnits() == null
                || dto.getQuoteHash() != null || dto.getShopChoices() != null && !dto.getShopChoices().isEmpty())
            throw new BusinessException(400, "活动结算参数组合不正确");
        String content = "ACTIVITY|" + dto.getBusinessType() + "|" + dto.getActivitySkuId() + "|"
                + dto.getSkuId() + "|" + dto.getQuantity() + "|" + dto.getGroupId() + "|"
                + dto.getBargainId() + "|" + dto.getAddressId() + "|"
                + dto.getConfirmedTotal().stripTrailingZeros().toPlainString() + "|" + dto.getConfirmedPointUnits();
        CheckoutVO existing = completedCheckoutIfPresent(userId, dto.getIdempotencyKey(), content);
        if (existing != null)
            return existing;
        Sku listed = products.findSku(dto.getSkuId());
        if (listed == null)
            throw new BusinessException(404, "SKU不存在");
        Product listedProduct = products.findProduct(listed.getProductId());
        if (listedProduct == null)
            throw new BusinessException(404, "商品不存在");
        Long shopId = listedProduct.getShopId();
        Long ownerAtOrder = shops.lockById(shopId).getOwnerUserId();
        Long requestId = beginCheckout(userId, dto.getIdempotencyKey(), content);
        if (requestId < 0)
            return completedCheckout(-requestId);
        MarketingOrderSource source = settlement.prepareSource(userId, dto.getBusinessType(),
                dto.getActivitySkuId(), dto.getQuantity(), dto.getGroupId(), dto.getBargainId(),
                dto.getIdempotencyKey());
        if (!Objects.equals(shopId, source.shopId()) || !Objects.equals(dto.getSkuId(), source.skuId())
                || !Objects.equals(dto.getActivitySkuId(), source.activitySkuId())
                || dto.getQuantity() != source.quantity() || !dto.getBusinessType().equals(source.businessType()))
            throw new BusinessException(409, "活动来源已变化");
        if (dto.getConfirmedTotal().compareTo(source.cashTotal()) != 0
                || "POINTS".equals(source.businessType()) && dto.getConfirmedPointUnits() != source.pointUnits())
            throw new BusinessException(409, "报价已变化，请重新确认",
                    Map.of(REASON, "QUOTE_CHANGED"), null);
        BigDecimal original = listed.getPrice().multiply(BigDecimal.valueOf(source.quantity()));
        if (source.cashTotal().signum() < 0 || original.compareTo(source.cashTotal()) < 0)
            throw new BusinessException(409, "活动报价无效");
        Address address = addresses.forCheckout(userId, dto.getAddressId());
        LocalDateTime now = LocalDateTime.now(businessClock);
        boolean pointsOrder = "POINTS".equals(source.businessType());
        boolean internallyPaid = pointsOrder || source.cashTotal().signum() == 0;
        LocalDateTime deadline = now.plusMinutes(15).withNano(0);
        if (source.expiresAt() == null || !now.isBefore(source.expiresAt()))
            throw new BusinessException(409, "活动已结束");
        if (source.expiresAt().isBefore(deadline))
            deadline = source.expiresAt();
        Order order = new Order();
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setShopOwnerUserIdAtOrder(ownerAtOrder);
        order.setBusinessType(source.businessType());
        order.setSettlementKind(pointsOrder ? "POINTS" : internallyPaid ? "ZERO_CASH" : "CASH");
        order.setFulfillmentState("GROUP".equals(source.businessType()) ? "WAIT_GROUP" : "READY");
        order.setStatus(internallyPaid ? OrderStatus.PAID : OrderStatus.UNPAID);
        order.setTotalAmount(source.cashTotal());
        order.setOriginalAmount(pointsOrder ? BigDecimal.ZERO : original);
        order.setDiscountAmount(pointsOrder ? BigDecimal.ZERO : original.subtract(source.cashTotal()));
        order.setPlatformSubsidyAmount(BigDecimal.ZERO);
        order.setPricingVersion(1);
        if (internallyPaid)
            order.setPaidAt(now);
        else
            order.setPayExpiresAt(deadline);
        snapshotAddress(order, userId, address);
        orderMapper.insert(order);
        memberRewards.prepareOrder(userId);
        Product product = products.requireMarketableProductForUpdate(shopId, source.productId());
        Sku sku = products.lockSku(source.skuId());
        if (sku == null || !Objects.equals(sku.getProductId(), product.getId())
                || !"ACTIVE".equals(sku.getStatus()))
            throw new BusinessException(409, "SKU已停售");
        if (sku.getPrice().compareTo(listed.getPrice()) != 0)
            throw new BusinessException(409, "商品价格已变化，请重新确认");
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setSkuId(sku.getId());
        item.setProductName(product.getName());
        item.setSkuSpec(String.join(" / ", products.optionNames(sku.getId())));
        item.setQuantity(source.quantity());
        item.setUnitPrice(pointsOrder ? BigDecimal.ZERO
                : source.cashTotal().divide(BigDecimal.valueOf(source.quantity())));
        item.setOriginalUnitPrice(sku.getPrice());
        item.setDiscountAmount(pointsOrder ? BigDecimal.ZERO : order.getDiscountAmount());
        item.setPayableAmount(source.cashTotal());
        item.setItemKind(pointsOrder ? "REDEEM" : "SALE");
        item.setSourceActivityId(source.activityId());
        orderItemMapper.insert(item);
        settlement.reserve(order.getId(), shopId, userId, internallyPaid ? now : deadline, null,
                Map.of(sku.getId(), item.getId()), List.of(), source.participationId());
        memberRewards.orderCreated(userId, ownerAtOrder, now,
                List.of(new RewardLine(item.getId(), source.cashTotal(), BigDecimal.ZERO)));
        if (internallyPaid) {
            settlement.finish(order.getId(), userId, true);
            if (source.groupId() != null)
                orderMapper.releaseSucceededGroup(source.groupId());
            stock.recordSale(product.getId(), source.quantity());
        }
        stock.refreshProducts(List.of(product.getId()));
        addresses.markUsed(userId, address.getId(), now);
        checkoutRequestMapper.linkOrder(requestId, order.getId());
        checkoutRequestMapper.markCompleted(requestId);
        BusinessEventLog.committed("trade", "order_created", "order", order.getId());
        return new CheckoutVO(List.of(CheckoutVO.PlacedOrder.from(order)), source.cashTotal(), 0);
    }

    /**
     * 锁定并检查各 SKU，按店铺拆单并核对用户确认的总价。
     * 所有店铺订单共用当前事务；任一店铺失败时，订单、库存和购物车改动一起回滚。
     */
    private CheckoutVO placeOrders(Long userId, Address address, List<CartItem> selected,
                                   BigDecimal confirmedTotal, Long requestId, String quoteHash,
                                   List<ShopChoice> choices) {
        if (quoteHash != null && !marketing.enabled())
            throw new BusinessException(409, "营销结算未启用");
        Map<Long, List<CheckoutLine>> byShop = new LinkedHashMap<>();
        // 店铺锁必须先于 SKU；否则与商家改价的店铺→SKU锁顺序相反。
        Set<Long> shopIds = new TreeSet<>();
        for (CartItem item : selected) {
            Sku sku = products.findSku(item.getSkuId());
            if (sku == null)
                throw new BusinessException(404, "SKU不存在");
            shopIds.add(products.findProduct(sku.getProductId()).getShopId());
        }
        Map<Long, Long> owners = new HashMap<>();
        for (Long shopId : shopIds)
            owners.put(shopId, shops.lockById(shopId).getOwnerUserId());
        String level = memberRewards.prepareOrder(userId);
        BigDecimal total = BigDecimal.ZERO;
        // 固定 SKU 加锁顺序，降低不同结算请求交叉锁定时的死锁概率。
        for (CartItem item : selected.stream().sorted(Comparator.comparing(CartItem::getSkuId)).toList()) {
            Sku sku = products.lockSku(item.getSkuId());
            if (sku == null)
                throw new BusinessException(404, "SKU不存在");
            Product product = products.findProduct(sku.getProductId());
            String reason = products.unavailableReason(sku, product, item.getQuantity());
            if (reason != null) {
                throw new BusinessException(409, reason,
                        Map.of(REASON, "库存不足".equals(reason) ? "OUT_OF_STOCK" : "UNAVAILABLE",
                                "skuId", item.getSkuId()), null);
            }
            total = total.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            byShop.computeIfAbsent(product.getShopId(), ignored -> new ArrayList<>())
                    .add(new CheckoutLine(item, sku, product));
        }
        MarketingPricingService.Price priced = null;
        if (marketing.enabled()) {
            if (level == null)
                throw new BusinessException(409, "营销结算需要启用会员");
            var input = byShop.entrySet().stream().flatMap(group -> group.getValue().stream().map(line ->
                    new MarketingPricingService.Line(line.sku().getId(), group.getKey(), owners.get(group.getKey()),
                            line.item().getQuantity(), line.sku().getPrice()))).toList();
            priced = pricing.price(userId, level, input, choices == null ? List.of() : choices,
                    CheckoutQuoteServiceImpl.addressHash(contentHash, address), true);
            total = priced.quote().totalAmount();
            if ((quoteHash != null && !quoteHash.equals(priced.quote().quoteHash())) ||
                    total.compareTo(confirmedTotal) != 0)
                throw new BusinessException(409, "报价已变化，请重新确认",
                        Map.of(REASON, "QUOTE_CHANGED", "quote", priced.quote()), null);
        } else if (total.compareTo(confirmedTotal) != 0) {
            throw new BusinessException(409, "价格已变化，请重新确认",
                    Map.of(REASON, "PRICE_CHANGED", "latestTotal", total), null);
        }

        List<CheckoutVO.PlacedOrder> orders = new ArrayList<>();
        for (Map.Entry<Long, List<CheckoutLine>> group : byShop.entrySet()) {
            var shopPrice = priced == null ? null : priced.quote().shops().stream()
                    .filter(q -> q.shopId().equals(group.getKey())).findFirst().orElseThrow();
            var discounts =
                    priced == null ? List.<MarketingPricingService.Allocation>of() : priced.allocations().stream()
                            .filter(a -> a.shopId().equals(group.getKey())).toList();
            orders.add(placeShopOrder(userId, address, group.getKey(), owners.get(group.getKey()), group.getValue(),
                    requestId, shopPrice, level, discounts));
        }
        stock.refreshProducts(byShop.values().stream().flatMap(List::stream)
                .map(line -> line.product().getId()).toList());
        addresses.markUsed(userId, address.getId(), LocalDateTime.now(businessClock));
        checkoutRequestMapper.markCompleted(requestId);
        return new CheckoutVO(orders, total, 0);
    }

    /** 为一个店铺创建订单与收货快照、扣库存并记流水，结算购物车项随后删除。 */
    private CheckoutVO.PlacedOrder placeShopOrder(Long userId, Address address, Long shopId, Long ownerAtOrder,
                                                  List<CheckoutLine> lines, Long requestId, QuoteVO.Shop quote,
                                                  String level,
                                                  List<MarketingPricingService.Allocation> discounts) {
        BigDecimal shopTotal = lines.stream()
                .map(line -> line.sku().getPrice().multiply(BigDecimal.valueOf(line.item().getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Order order = new Order();
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setShopOwnerUserIdAtOrder(ownerAtOrder);
        if (quote != null) {
            shopTotal = quote.totalAmount();

            order.setMemberLevelCode(level);
            order.setDiscountAmount(quote.discountAmount());
            order.setPlatformSubsidyAmount(discounts.stream().map(MarketingPricingService.Allocation::platformAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            order.setPricingVersion(1);
        }
        order.setBusinessType("NORMAL");
        boolean zeroCash = shopTotal.signum() == 0;
        order.setSettlementKind(zeroCash ? "ZERO_CASH" : "CASH");
        order.setFulfillmentState("READY");
        order.setTotalAmount(shopTotal);
        order.setOriginalAmount(quote == null ? shopTotal : quote.originalAmount());
        snapshotAddress(order, userId, address);
        order.setStatus(zeroCash ? OrderStatus.PAID : OrderStatus.UNPAID);
        if (zeroCash)
            order.setPaidAt(LocalDateTime.now(businessClock));
        if (!zeroCash)
            order.setPayExpiresAt(LocalDateTime.now(businessClock).plusMinutes(15).withNano(0));
        orderMapper.insert(order);
        List<RewardLine> rewardLines = new ArrayList<>();
        Map<Long, Long> itemIds = new HashMap<>();
        Map<Long, QuoteVO.Item> quoteItems = quote == null ? Map.of() : quote.items().stream().collect(
                Collectors.toMap(QuoteVO.Item::skuId, i -> i));
        for (CheckoutLine line : lines) {
            String spec = String.join(" / ", products.optionNames(line.sku().getId()));
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setSkuId(line.sku().getId());
            item.setProductName(line.product().getName());
            item.setSkuSpec(spec);
            item.setQuantity(line.item().getQuantity());
            item.setUnitPrice(line.sku().getPrice());
            item.setOriginalUnitPrice(item.getUnitPrice());
            item.setPayableAmount(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            if (quote != null) {
                var snapshot = quoteItems.get(item.getSkuId());
                item.setUnitPrice(snapshot.unitPrice());
                item.setPayableAmount(snapshot.payableAmount());
                item.setDiscountAmount(snapshot.discountAmount());
            }
            orderItemMapper.insert(item);
            itemIds.put(item.getSkuId(), item.getId());
            Long itemId = item.getId();
            rewardLines.add(new RewardLine(itemId, item.getPayableAmount(), BigDecimal.ZERO));
            stock.deductForOrder(line.sku().getId(), itemId, line.item().getQuantity(),
                    line.sku().getStock() - line.item().getQuantity(),
                    "CHECKOUT_DEDUCT:" + order.getId() + ":" + line.sku().getId());
            if (line.item().getId() != null)
                cartItemMapper.deleteById(line.item().getId());
        }
        if (!discounts.isEmpty())
            settlement.reserve(order.getId(), shopId, userId, zeroCash ? order.getPaidAt() : order.getPayExpiresAt(),
                    quote.selectedUserCouponId(), itemIds, discounts, null);
        memberRewards.orderCreated(userId, ownerAtOrder, LocalDateTime.now(businessClock), rewardLines);
        if (zeroCash) {
            settlement.finish(order.getId(), userId, true);
            for (var line : lines)
                stock.recordSale(line.product().getId(), line.item().getQuantity());
        }
        checkoutRequestMapper.linkOrder(requestId, order.getId());
        BusinessEventLog.committed("trade", "order_created", "order", order.getId());
        return CheckoutVO.PlacedOrder.from(order);
    }

    private void snapshotAddress(Order order, Long userId, Address address) {
        // 订单保留独立的收货快照；后续修改或删除地址不应改变历史订单。
        order.setConsigneeCiphertext(privacyCipher.encrypt(userId, "orders:consignee",
                privacyCipher.decrypt(userId, "consignee", address.getKeyVersion(), address.getConsigneeCiphertext())));
        order.setPhoneCiphertext(privacyCipher.encrypt(userId, "orders:phone",
                privacyCipher.decrypt(userId, "phone", address.getKeyVersion(), address.getPhoneCiphertext())));
        order.setProvince(address.getProvince());
        order.setCity(address.getCity());
        order.setDistrict(address.getDistrict());
        order.setDetailCiphertext(privacyCipher.encrypt(userId, "orders:detail",
                privacyCipher.decrypt(userId, "detail", address.getKeyVersion(), address.getDetailCiphertext())));
        order.setShippingKeyVersion(1);
    }

    /**
     * 记录并锁定结算请求，比较请求摘要，禁止相同幂等键用于不同内容。
     * 负 ID 表示已有完成结果，可原样返回；处理中请求返回冲突。
     */
    private Long beginCheckout(Long userId, String key, String content) {
        byte[] hash = requestHash(content);
        boolean created = true;
        try {
            checkoutRequestMapper.insertProcessing(userId, key, hash);
        } catch (DuplicateKeyException e) {
            created = false;
        }
        Map<String, Object> row = checkoutRequestMapper.selectForUpdate(userId, key);
        if (!HexFormat.of().formatHex(hash).equalsIgnoreCase((String) row.get("request_hash"))) {
            throw new BusinessException(409, "幂等键已用于其他结算请求");
        }
        Long id = ((Number) row.get("id")).longValue();
        if (created)
            return id;
        if (!"COMPLETED".equals(row.get("status")))
            throw new BusinessException(409, "结算正在处理");
        return -id;
    }

    private CheckoutVO completedCheckoutIfPresent(Long userId, String key, String content) {
        Map<String, Object> row = checkoutRequestMapper.selectExisting(userId, key);
        if (row == null)
            return null;
        if (!HexFormat.of().formatHex(requestHash(content)).equalsIgnoreCase((String) row.get("request_hash")))
            throw new BusinessException(409, "幂等键已用于其他结算请求");
        if (!"COMPLETED".equals(row.get("status")))
            throw new BusinessException(409, "结算正在处理");
        return completedCheckout(((Number) row.get("id")).longValue());
    }

    private byte[] requestHash(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 从已完成的幂等请求关联订单重建响应，重复提交不再扣库存或创建订单。 */
    private CheckoutVO completedCheckout(Long requestId) {
        List<CheckoutVO.PlacedOrder> orders = checkoutRequestMapper.selectOrders(requestId).stream()
                .map(CheckoutVO.PlacedOrder::from).toList();
        BigDecimal total = orders.stream().map(CheckoutVO.PlacedOrder::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CheckoutVO(orders, total, 0);
    }

    private void lockCheckoutShops(List<Long> skuIds) {
        Set<Long> shopIds = new TreeSet<>();
        for (Long skuId : skuIds) {
            Sku sku = products.findSku(skuId);
            if (sku == null)
                throw new BusinessException(404, "SKU不存在");
            Product product = products.findProduct(sku.getProductId());
            if (product == null)
                throw new BusinessException(404, "商品不存在");
            shopIds.add(product.getShopId());
        }
        for (Long shopId : shopIds)
            shops.lockById(shopId);
    }

    private record CheckoutLine(CartItem item, Sku sku, Product product) {
    }
}
