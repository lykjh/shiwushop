package com.shiwushop.commerce.service.impl;

import com.shiwushop.commerce.dto.cart.CartAddDTO;
import com.shiwushop.commerce.vo.cart.CartItemVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.commerce.entity.cart.CartItem;
import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.commerce.mapper.cart.CartItemMapper;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.vo.query.product.SkuOptionValueRow;
import com.shiwushop.commerce.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/** 只维护购物车内容；下单事务由 CheckoutServiceImpl 负责。 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final CartItemMapper cartItemMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;
    private final CategoryMapper categoryMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    private final ProductOptionValueMapper optionValueMapper;
    private final AccountAccess accountAccess;
    private final CartAvailability availability;

    /** 校验账号和 SKU 可售性后新增购物车项；重复 SKU 返回冲突，不会叠加数量。 */
    @Override
    public CartItemVO add(Long userId, CartAddDTO dto) {
        accountAccess.checkActive(userId);
        Sku sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException(404, "SKU不存在");
        }
        String reason = availability.unavailableReason(sku, productMapper.selectById(sku.getProductId()), dto.getQuantity());
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

    /** 批量读取购物车关联的商品、店铺、分类和规格，逐项标明当前不可购买原因。 */
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
            String reason = availability.unavailableReason(sku, product, item.getQuantity(), shop,
                    product != null && categoryVisible(product.getCategoryId(), categories));
            return new CartItemVO(item.getId(), item.getSkuId(),
                    product == null ? null : product.getId(),
                    product == null ? null : product.getName(),
                    sku == null ? List.of() : optionNames.getOrDefault(sku.getId(), List.of()),
                    sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
        }).toList();
    }

    /** 去重后每批最多查询 200 个 ID，避免购物车展示时为每项分别访问数据库。 */
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

    /** 沿已批量装载的分类父链检查可见性；缺少分类也视为不可购买。 */
    private boolean categoryVisible(Long id, Map<Long, Category> categories) {
        Category current = categories.get(id);
        if (current == null) return false;
        while (current != null) {
            if (!Integer.valueOf(1).equals(current.getVisible())) return false;
            current = current.getParentId() == null ? null : categories.get(current.getParentId());
        }
        return true;
    }

    /** 仅修改当前用户的购物车项；写入行数为零时报告该项已不存在。 */
    @Override
    public CartItemVO updateQuantity(Long userId, Long id, Integer quantity) {
        CartItem item = ownedItem(userId, id);
        item.setQuantity(quantity);
        if (cartItemMapper.updateById(item) != 1) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        return toVO(item);
    }

    /** 校验归属后删除购物车项；已被并发删除时返回不存在。 */
    @Override
    public void delete(Long userId, Long id) {
        ownedItem(userId, id);
        if (cartItemMapper.deleteById(id) != 1) {
            throw new BusinessException(404, "购物车商品不存在");
        }
    }

    /** 按用户和条目 ID 联合查询，防止操作其他用户的购物车。 */
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

    /** 将新建或修改的单项转换为响应，并重新计算当前可售状态。 */
    private CartItemVO toVO(CartItem item) {
        Sku sku = skuMapper.selectById(item.getSkuId());
        Product product = sku == null ? null : productMapper.selectById(sku.getProductId());
        String reason = availability.unavailableReason(sku, product, item.getQuantity());
        List<String> values = sku == null ? List.of() : skuOptionValueMapper.selectValueIds(sku.getId())
                .stream().map(optionValueMapper::selectById)
                .filter(Objects::nonNull)
                .map(ProductOptionValue::getValueName).toList();
        return new CartItemVO(item.getId(), item.getSkuId(),
                product == null ? null : product.getId(),
                product == null ? null : product.getName(), values,
                sku == null ? null : sku.getPrice(), item.getQuantity(), reason == null, reason);
    }


}
