package com.shiwushop.commerce.service.impl;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.entity.address.Address;
import com.shiwushop.account.mapper.address.AddressMapper;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import com.shiwushop.commerce.dto.cart.DirectCheckoutDTO;
import com.shiwushop.commerce.entity.cart.CartItem;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.mapper.cart.CartItemMapper;
import com.shiwushop.commerce.mapper.order.CheckoutRequestMapper;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.commerce.service.CheckoutService;
import com.shiwushop.commerce.vo.order.CheckoutVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.outbox.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
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
    private static final String REASON = "reason";
    private final AccountAccess accountAccess;
    private final CartItemMapper cartItemMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final AddressMapper addressMapper;
    private final CheckoutRequestMapper checkoutRequestMapper;
    private final InventoryMovementMapper inventoryMovementMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderMapper orderMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    private final ProductOptionValueMapper optionValueMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final PrivacyCipher privacyCipher;
    private final Clock businessClock;
    private final CartAvailability availability;
    /** 校验所选购物车项和幂等键，锁定归属条目后在一个事务内创建订单。 */
    @Override
    @Transactional
    public CheckoutVO checkoutSelected(Long userId, CheckoutDTO dto) {
        accountAccess.checkActive(userId);
        if (dto == null || dto.getCartItemIds() == null || dto.getCartItemIds().isEmpty()
                || dto.getCartItemIds().size() > CheckoutDTO.MAX_CART_ITEMS
                || dto.getCartItemIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(400, "购物车项数量或ID无效");
        }
        if (new HashSet<>(dto.getCartItemIds()).size() != dto.getCartItemIds().size()) {
            throw new BusinessException(400, "购物车项不能重复");
        }
        String content = "CART|" + dto.getCartItemIds().stream().sorted().toList()
                + "|" + dto.getAddressId() + "|" + dto.getConfirmedTotal().stripTrailingZeros().toPlainString();
        Long requestId = beginCheckout(userId, dto.getIdempotencyKey(), content);
        if (requestId < 0) return completedCheckout(-requestId);
        Address address = ownedAddress(userId, dto.getAddressId());

        List<CartItem> selected = new ArrayList<>();
        for (Long id : dto.getCartItemIds().stream().sorted().toList()) {
            CartItem item = cartItemMapper.selectOwnedForUpdate(userId, id);
            if (item == null) throw new BusinessException(404, "购物车商品不存在");
            selected.add(item);
        }
        return placeOrders(userId, address, selected, dto.getConfirmedTotal(), requestId);
    }

    /** 将直接购买转换为临时结算项；复用订单、库存及幂等处理，不写购物车。 */
    @Override
    @Transactional
    public CheckoutVO checkoutDirect(Long userId, DirectCheckoutDTO dto) {
        accountAccess.checkActive(userId);
        String content = "DIRECT|" + dto.getSkuId() + "|" + dto.getQuantity()
                + "|" + dto.getAddressId() + "|" + dto.getConfirmedTotal().stripTrailingZeros().toPlainString();
        Long requestId = beginCheckout(userId, dto.getIdempotencyKey(), content);
        if (requestId < 0) return completedCheckout(-requestId);
        Address address = ownedAddress(userId, dto.getAddressId());
        if (skuMapper.selectById(dto.getSkuId()) == null) {
            throw new BusinessException(404, "SKU不存在");
        }
        CartItem item = new CartItem();
        item.setSkuId(dto.getSkuId());
        item.setQuantity(dto.getQuantity());
        return placeOrders(userId, address, List.of(item), dto.getConfirmedTotal(), requestId);
    }

    /** 只接受当前用户仍有效的地址，结算时据此生成订单收货快照。 */
    private Address ownedAddress(Long userId, Long addressId) {
        Address address = addressMapper.selectOwnedActive(userId, addressId);
        if (address == null) throw new BusinessException(404, "地址不存在");
        return address;
    }

    /**
     * 锁定并检查各 SKU，按店铺拆单并核对用户确认的总价。
     * 所有店铺订单共用当前事务；任一店铺失败时，订单、库存和购物车改动一起回滚。
     */
    private CheckoutVO placeOrders(Long userId, Address address, List<CartItem> selected,
                                   BigDecimal confirmedTotal, Long requestId) {
        Map<Long, List<CheckoutLine>> byShop = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        // 固定 SKU 加锁顺序，降低不同结算请求交叉锁定时的死锁概率。
        for (CartItem item : selected.stream().sorted(Comparator.comparing(CartItem::getSkuId)).toList()) {
            Sku sku = skuMapper.selectForUpdate(item.getSkuId());
            if (sku == null) throw new BusinessException(404, "SKU不存在");
            Product product = productMapper.selectById(sku.getProductId());
            String reason = availability.unavailableReason(sku, product, item.getQuantity());
            if (reason != null) {
                throw new BusinessException(409, reason,
                        Map.of(REASON, "库存不足".equals(reason) ? "OUT_OF_STOCK" : "UNAVAILABLE",
                                "skuId", item.getSkuId()), null);
            }
            total = total.add(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            byShop.computeIfAbsent(product.getShopId(), ignored -> new ArrayList<>())
                    .add(new CheckoutLine(item, sku, product));
        }
        if (total.compareTo(confirmedTotal) != 0) {
            throw new BusinessException(409, "价格已变化，请重新确认",
                    Map.of(REASON, "PRICE_CHANGED", "latestTotal", total), null);
        }

        List<CheckoutVO.PlacedOrder> orders = new ArrayList<>();
        for (Map.Entry<Long, List<CheckoutLine>> group : byShop.entrySet()) {
            orders.add(placeShopOrder(userId, address, group.getKey(), group.getValue(), requestId));
        }
        byShop.values().stream().flatMap(List::stream)
                .map(line -> line.product().getId()).distinct()
                .forEach(productId -> {
                    if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
                        throw new IllegalStateException("商品搜索事件写入失败");
                    }
                });
        addressMapper.markUsed(userId, address.getId(), LocalDateTime.now(businessClock));
        checkoutRequestMapper.markCompleted(requestId);
        return new CheckoutVO(orders, total);
    }

    /** 为一个店铺创建订单与收货快照、扣库存并记流水，结算购物车项随后删除。 */
    private CheckoutVO.PlacedOrder placeShopOrder(Long userId, Address address, Long shopId,
                                                  List<CheckoutLine> lines, Long requestId) {
        BigDecimal shopTotal = lines.stream()
                .map(line -> line.sku().getPrice().multiply(BigDecimal.valueOf(line.item().getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Order order = new Order();
        order.setUserId(userId);
        order.setShopId(shopId);
        order.setTotalAmount(shopTotal);
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
        order.setStatus(OrderStatus.UNPAID);
        order.setPayExpiresAt(LocalDateTime.now(businessClock).plusMinutes(15));
        orderMapper.insert(order);
        for (CheckoutLine line : lines) {
            if (skuMapper.decreaseStock(line.sku().getId(), line.item().getQuantity()) != 1) {
                throw new BusinessException(409, "库存不足",
                        Map.of(REASON, "OUT_OF_STOCK", "skuId", line.sku().getId()), null);
            }
            String spec = String.join(" / ", skuOptionValueMapper.selectValueIds(line.sku().getId())
                    .stream().map(optionValueMapper::selectById).filter(Objects::nonNull)
                    .map(ProductOptionValue::getValueName).toList());
            orderMapper.insertCheckoutItem(order.getId(), line.sku().getId(),
                    line.product().getName(), spec, line.item().getQuantity(),
                    line.sku().getPrice());
            Long itemId = orderItemMapper.selectLatestId(order.getId(), line.sku().getId());
            inventoryMovementMapper.insertOrderDeduct(line.sku().getId(), itemId, -line.item().getQuantity(),
                    line.sku().getStock() - line.item().getQuantity(),
                    "CHECKOUT_DEDUCT:" + order.getId() + ":" + line.sku().getId());
            if (line.item().getId() != null) cartItemMapper.deleteById(line.item().getId());
        }
        checkoutRequestMapper.linkOrder(requestId, order.getId());
        BusinessEventLog.committed("commerce", "order_created", "order", order.getId());
        return new CheckoutVO.PlacedOrder(order.getId(), shopId, shopTotal, "UNPAID");
    }

    /**
     * 记录并锁定结算请求，比较请求摘要，禁止相同幂等键用于不同内容。
     * 负 ID 表示已有完成结果，可原样返回；处理中请求返回冲突。
     */
    private Long beginCheckout(Long userId, String key, String content) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        if (created) return id;
        if (!"COMPLETED".equals(row.get("status"))) throw new BusinessException(409, "结算正在处理");
        return -id;
    }

    /** 从已完成的幂等请求关联订单重建响应，重复提交不再扣库存或创建订单。 */
    private CheckoutVO completedCheckout(Long requestId) {
        List<CheckoutVO.PlacedOrder> orders = checkoutRequestMapper.selectOrders(requestId).stream()
                .map(order -> new CheckoutVO.PlacedOrder(order.getId(), order.getShopId(),
                        order.getTotalAmount(), order.getStatus().name())).toList();
        BigDecimal total = orders.stream().map(CheckoutVO.PlacedOrder::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CheckoutVO(orders, total);
    }

    private record CheckoutLine(CartItem item, Sku sku, Product product) {}

}
