package com.shiwushop.product.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.product.mapper.product.InventoryMovementMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.product.service.InventoryMovementService;
import com.shiwushop.product.vo.inventory.InventoryMovementPageVO;
import com.shiwushop.product.vo.inventory.InventoryMovementVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryMovementServiceImpl implements InventoryMovementService {
    private final MerchantShopAccess shops;
    private final SkuMapper skus;
    private final ProductMapper products;
    private final InventoryMovementMapper movements;

    /** 只读本店 SKU 流水；按 ID 游标分页，新写入不改变后续页边界。 */
    @Override
    public InventoryMovementPageVO list(Long userId, Long shopId, Long skuId, InventoryMovementQuery query) {
        shops.requireOwned(userId, shopId, "商品不存在");
        Sku sku = skus.selectById(skuId);
        Product product = sku == null ? null : products.selectById(sku.getProductId());
        if (product == null || !shopId.equals(product.getShopId()))
            throw new BusinessException(404, "商品不存在");
        int size = query.size() == null ? 20 : query.size();
        List<InventoryMovementVO> rows = movements.selectPage(skuId, query, size + 1);
        boolean hasMore = rows.size() > size;
        List<InventoryMovementVO> items = hasMore ? List.copyOf(rows.subList(0, size)) : rows;
        return new InventoryMovementPageVO(items, hasMore, hasMore ? items.getLast().id() : null);
    }
}
