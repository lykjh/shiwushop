package com.shiwushop.service.impl;

import com.shiwushop.dto.cart.CartAddDTO;
import com.shiwushop.dto.cart.CheckoutDTO;
import com.shiwushop.dto.cart.DirectCheckoutDTO;
import com.shiwushop.vo.cart.CartItemVO;
import com.shiwushop.vo.order.CheckoutVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.AccountAccess;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.entity.address.Address;
import com.shiwushop.entity.cart.CartItem;
import com.shiwushop.entity.category.Category;
import com.shiwushop.entity.order.Order;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.product.Sku;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.entity.product.ProductOptionValue;
import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.entity.status.ProductStatus;
import com.shiwushop.mapper.address.AddressMapper;
import com.shiwushop.mapper.order.CheckoutRequestMapper;
import com.shiwushop.mapper.cart.CartItemMapper;
import com.shiwushop.mapper.category.CategoryMapper;
import com.shiwushop.mapper.product.InventoryMovementMapper;
import com.shiwushop.mapper.order.OrderItemMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.mapper.shop.ShopMapper;
import com.shiwushop.mapper.product.SkuOptionValueMapper;
import com.shiwushop.mapper.product.ProductOptionValueMapper;
import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.vo.query.product.SkuOptionValueRow;
import com.shiwushop.service.CartService;
import com.shiwushop.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private static final String REASON = "reason";

    private final CartItemMapper cartItemMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final CategoryMapper categoryMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    private final ProductOptionValueMapper optionValueMapper;
    private final CategoryService categoryService;
    private final AddressMapper addressMapper;
    private final CheckoutRequestMapper checkoutRequestMapper;
    private final InventoryMovementMapper inventoryMovementMapper;
    private final OrderItemMapper orderItemMapper;
    private final Clock businessClock;
    private final OrderMapper orderMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final AccountAccess accountAccess;
    private final PrivacyCipher privacyCipher;

    @Override
    public CartItemVO add(Long userId, CartAddDTO dto) {
        accountAccess.checkActive(userId);
        Sku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(404, "SKU不存在");
        }
        String reason = unavailableReason(sku, productMapper.selectById(sku.getProductId()), dto.getQuantity());
        if (reason != null) {
            throw new BusinessException(409, reason);
        }
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setSkuId(dto.getSkuId());
        item.setQuantity(dto.getQuantity());
        try {
            cartItemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "商品已在购物车中", e);
        }
        return toVO(item);
    }

    @Override
    public List<CartItemVO> list(Long userId) {
        accountAccess.checkActive(userId);
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId).orderByAsc(CartItem::getId));
        if (items.isEmpty()) return List.of();

        Map<Long, Sku> skus = loadById(items.stream().map(CartItem::getSkuId).toList(),
                skuMapper::selectBatchIds, Sku::getId);
        Map<Long, Product> products = loadById(skus.values().stream().map(Sku::getProductId).toList(),
                productMapper::selectBatchIds, Product::getId);
        Map<Long, Shop> shops = loadById(products.values().stream().map(Product::getShopId)
                .filter(Objects::nonNull).toList(), shopMapper::selectBatchIds, Shop::getId);

        // 分类最多三级；分层装载祖先，保持原有“任一级隐藏则不可购买”的判断。
        Map<Long, Category> categories = new HashMap<>();
        Collection<Long> nextLevel = products.values().stream().map(Product::getCategoryId)
                .filter(Objects::nonNull).toList();
        for (int level = 0; level < 3 && !nextLevel.isEmpty(); level++) {
            Map<Long, Category> loaded = loadById(nextLevel, categoryMapper::selectBatchIds, Category::getId);
            categories.putAll(loaded);
            nextLevel = loaded.values().stream().map(Category::getParentId)
                    .filter(Objects::nonNull).filter(id -> !categories.containsKey(id)).toList();
        }

        Map<Long, List<String>> optionNames = new HashMap<>();
        List<Long> skuIds = new ArrayList<>(skus.keySet());
        for (int start = 0; start < skuIds.size(); start += 200) {
            for (SkuOptionValueRow row : skuOptionValueMapper.selectSkuValues(
                    skuIds.subList(start, Math.min(start + 200, skuIds.size())))) {
                optionNames.computeIfAbsent(row.getSkuId(), ignored -> new ArrayList<>())
                        .add(row.getValueName());
            }
        }

        return items.stream().map(item -> {
            Sku sku = skus.get(item.getSkuId());
            Product product = sku == null ? null : products.get(sku.getProductId());
            Shop shop = product == null ? null : shops.get(product.getShopId());
            String reason = unavailableReason(sku, product, item.getQuantity(), shop,
                    product != null && categoryVisible(product.getCategoryId(), categories));
            return new CartItemVO(item.getId(), item.getSkuId(),
                    product == null ? null : product.getId(),
                    product == null ? null : product.getName(),
                    sku == null ? List.of() : optionNames.getOrDefault(sku.getId(), List.of()),
                    sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
        }).toList();
    }

    private <T> Map<Long, T> loadById(Collection<Long> ids,
                                       Function<Collection<Long>, List<T>> select,
                                       Function<T, Long> idOf) {
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, T> result = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += 200) {
            for (T value : select.apply(distinctIds.subList(start, Math.min(start + 200, distinctIds.size())))) {
                result.put(idOf.apply(value), value);
            }
        }
        return result;
    }

    private boolean categoryVisible(Long id, Map<Long, Category> categories) {
        Category current = categories.get(id);
        if (current == null) return false;
        while (current != null) {
            if (!Integer.valueOf(1).equals(current.getVisible())) return false;
            current = current.getParentId() == null ? null : categories.get(current.getParentId());
        }
        return true;
    }

    @Override
    public CartItemVO updateQuantity(Long userId, Long id, Integer quantity) {
        CartItem item = ownedItem(userId, id);
        item.setQuantity(quantity);
        cartItemMapper.updateById(item);
        return toVO(item);
    }

    @Override
    public void delete(Long userId, Long id) {
        ownedItem(userId, id);
        cartItemMapper.deleteById(id);
    }

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

    private Address ownedAddress(Long userId, Long addressId) {
        Address address = addressMapper.selectOwnedActive(userId, addressId);
        if (address == null) throw new BusinessException(404, "地址不存在");
        return address;
    }

    /** 所有店铺订单共用当前事务；任一店铺失败时，订单、库存和购物车改动一起回滚。 */
    private CheckoutVO placeOrders(Long userId, Address address, List<CartItem> selected,
                                   BigDecimal confirmedTotal, Long requestId) {
        Map<Long, List<CheckoutLine>> byShop = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        // 固定 SKU 加锁顺序，降低不同结算请求交叉锁定时的死锁概率。
        for (CartItem item : selected.stream().sorted(Comparator.comparing(CartItem::getSkuId)).toList()) {
            Sku sku = skuMapper.selectForUpdate(item.getSkuId());
            if (sku == null) throw new BusinessException(404, "SKU不存在");
            Product product = productMapper.selectById(sku.getProductId());
            String reason = unavailableReason(sku, product, item.getQuantity());
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
        return new CheckoutVO.PlacedOrder(order.getId(), shopId, shopTotal, "UNPAID");
    }

    /** 同一幂等键只允许同一请求内容；负 ID 表示已有完成结果，可原样返回。 */
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

    private CheckoutVO completedCheckout(Long requestId) {
        List<CheckoutVO.PlacedOrder> orders = checkoutRequestMapper.selectOrders(requestId).stream()
                .map(order -> new CheckoutVO.PlacedOrder(order.getId(), order.getShopId(),
                        order.getTotalAmount(), order.getStatus().name())).toList();
        BigDecimal total = orders.stream().map(CheckoutVO.PlacedOrder::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CheckoutVO(orders, total);
    }

    private record CheckoutLine(CartItem item, Sku sku, Product product) {}

    private CartItem ownedItem(Long userId, Long id) {
        accountAccess.checkActive(userId);
        CartItem item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getId, id)
                .eq(CartItem::getUserId, userId));
        if (item == null) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        return item;
    }

    private CartItemVO toVO(CartItem item) {
        Sku sku = skuMapper.selectById(item.getSkuId());
        Product product = sku == null ? null : productMapper.selectById(sku.getProductId());
        String reason = unavailableReason(sku, product, item.getQuantity());
        List<String> values = sku == null ? List.of() : skuOptionValueMapper.selectValueIds(sku.getId())
                .stream().map(optionValueMapper::selectById)
                .filter(Objects::nonNull)
                .map(ProductOptionValue::getValueName).toList();
        return new CartItemVO(item.getId(), item.getSkuId(),
                product == null ? null : product.getId(),
                product == null ? null : product.getName(), values,
                sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
    }

    private String unavailableReason(Sku sku, Product product, int quantity) {
        if (sku == null || product == null) return "商品不存在";
        if (product.getStatus() != ProductStatus.ON_SALE) return "商品已下架";
        Shop shop = product.getShopId() == null ? null : shopMapper.selectById(product.getShopId());
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        boolean visible;
        try {
            categoryService.detail(product.getCategoryId());
            visible = true;
        } catch (BusinessException e) {
            visible = false;
        }
        return unavailableReason(sku, product, quantity, shop, visible);
    }

    private String unavailableReason(Sku sku, Product product, int quantity, Shop shop, boolean categoryVisible) {
        if (sku == null || product == null) return "商品不存在";
        if (product.getStatus() != ProductStatus.ON_SALE) return "商品已下架";
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        if (!categoryVisible) return "分类已隐藏";
        if (sku.getStock() == null || sku.getStock() < quantity) return "库存不足";
        return null;
    }
}
