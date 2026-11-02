package com.shiwushop.product.service.impl;

import com.shiwushop.product.service.TradingProductService;
import com.shiwushop.product.service.CategoryService;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.common.BusinessException;
import com.shiwushop.product.entity.category.Category;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.ProductOptionValue;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.mapper.category.CategoryMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.mapper.product.SkuOptionValueMapper;
import com.shiwushop.product.mapper.product.ProductOptionValueMapper;
import com.shiwushop.product.vo.query.product.SkuOptionValueRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;

/** 复用原购物车批量查询和可售性算法；调用方无需了解商品表之间的关联。 */
@Service
@RequiredArgsConstructor
public class TradingProductServiceImpl implements TradingProductService {
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final ShopService shopService;
    private final CategoryMapper categoryMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    private final ProductOptionValueMapper optionValueMapper;
    private final CategoryService categoryService;

    @Override
    public Sku findSku(Long id) { return skuMapper.selectById(id); }
    /** 加入调用方事务；锁在结算提交或回滚时一起释放。 */
    @Override
    public Sku lockSku(Long id) { return skuMapper.selectForUpdate(id); }
    @Override
    public Product findProduct(Long id) { return productMapper.selectById(id); }
    /** 查询店铺和分类状态后，返回该数量不可下单的原因；可购买时返回 null。 */
    @Override
    public String unavailableReason(Sku sku, Product product, int quantity) {
        String reason = productUnavailableReason(sku, product);
        if (reason != null) return reason;
        Shop shop = shopService.findById(product.getShopId());
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        boolean visible;
        try {
            categoryService.detail(product.getCategoryId());
            visible = true;
        } catch (BusinessException e) {
            visible = false;
        }
        return stockOrCategoryReason(sku, quantity, visible);
    }

    /** 复用调用方已装载的数据判定可售性，避免列表逐项重复查询。 */
    private String unavailableReason(Sku sku, Product product, int quantity, Shop shop, boolean categoryVisible) {
        String reason = productUnavailableReason(sku, product);
        if (reason != null) return reason;
        if (shop == null || !Integer.valueOf(1).equals(shop.getEnabled())) return "店铺已暂停营业";
        return stockOrCategoryReason(sku, quantity, categoryVisible);
    }

    private String productUnavailableReason(Sku sku, Product product) {
        if (sku == null || product == null) return "商品不存在";
        return product.getStatus() != ProductStatus.ON_SALE ? "商品已下架" : null;
    }

    private String stockOrCategoryReason(Sku sku, int quantity, boolean categoryVisible) {
        if (!categoryVisible) return "分类已隐藏";
        if (sku.getStock() < quantity) return "库存不足";
        return null;
    }
    @Override
    public List<String> optionNames(Long skuId) {
        return skuOptionValueMapper.selectValueIds(skuId).stream().map(optionValueMapper::selectById)
                .filter(Objects::nonNull).map(ProductOptionValue::getValueName).toList();
    }
    @Override
    public Map<Long, CartProduct> cartProducts(Map<Long, Integer> quantities) {
        Map<Long, Sku> skus = loadById(quantities.keySet(),
                skuMapper::selectBatchIds, Sku::getId);
        Map<Long, Product> products = loadById(skus.values().stream().map(Sku::getProductId).toList(),
                productMapper::selectBatchIds, Product::getId);
        Map<Long, Shop> shops = loadById(products.values().stream().map(Product::getShopId)
                .filter(Objects::nonNull).toList(), shopService::findByIds, Shop::getId);

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

        Map<Long, CartProduct> result = new HashMap<>();
        quantities.forEach((skuId, quantity) -> {
            Sku sku = skus.get(skuId);
            Product product = sku == null ? null : products.get(sku.getProductId());
            Shop shop = product == null ? null : shops.get(product.getShopId());
            String reason = unavailableReason(sku, product, quantity, shop,
                    product != null && categoryVisible(product.getCategoryId(), categories));
            result.put(skuId, new CartProduct(sku, product, optionNames.getOrDefault(skuId, List.of()), reason));
        });
        return result;
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

}
