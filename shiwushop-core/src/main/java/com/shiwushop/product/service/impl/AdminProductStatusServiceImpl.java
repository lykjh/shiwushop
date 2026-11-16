package com.shiwushop.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.status.ProductStatus;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.product.service.AdminProductStatusService;
import com.shiwushop.product.vo.product.ProductStatusVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 处理管理员下架商品。 */
@Service
@RequiredArgsConstructor
public class AdminProductStatusServiceImpl implements AdminProductStatusService {
    private final ProductMapper productMapper;
    private final ShopService shopService;

    /** 拒绝上架指令；状态写入失败时回滚。 */
    @Override
    @Transactional
    public ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "商品ID必须大于0");
        }
        if (!ProductStatus.OFF_SALE.name().equals(dto.getStatus())) {
            throw new BusinessException(400, "管理员只能下架商品");
        }
        Product current = productMapper.selectById(productId);
        if (current == null) {
            throw new BusinessException(404, "商品不存在");
        }
        shopService.lockById(current.getShopId());
        Product product = productMapper.selectForUpdate(productId);
        if (product == null || !current.getShopId().equals(product.getShopId()))
            throw new BusinessException(404, "商品不存在");
        if (product.getStatus() == ProductStatus.OFF_SALE)
            return new ProductStatusVO(productId, ProductStatus.OFF_SALE.name(), product.getRevision(), null);
        if (product.getStatus() != ProductStatus.ON_SALE)
            throw new BusinessException(409, "当前商品不可下架");
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .set(Product::getStatus, ProductStatus.OFF_SALE).setSql("revision = revision + 1"));
        BusinessEventLog.committed("product", "product_admin_taken_down", "product", productId);
        return new ProductStatusVO(productId, ProductStatus.OFF_SALE.name(), product.getRevision() + 1, null);
    }
}
