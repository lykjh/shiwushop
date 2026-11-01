package com.shiwushop.catalog.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.service.AdminProductStatusService;
import com.shiwushop.catalog.vo.product.ProductStatusVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.common.outbox.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只处理管理员下架，并在同一事务中投递搜索刷新事件。 */
@Service
@RequiredArgsConstructor
public class AdminProductStatusServiceImpl implements AdminProductStatusService {
    private final ProductMapper productMapper;
    private final OutboxEventMapper outboxEventMapper;

    /** 拒绝上架指令；状态写入与搜索事件任一失败时整体回滚。 */
    @Override
    @Transactional
    public ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "商品ID必须大于0");
        }
        if (!ProductStatus.OFF_SALE.name().equals(dto.getStatus())) {
            throw new BusinessException(400, "管理员只能下架商品");
        }
        if (productMapper.selectById(productId) == null) {
            throw new BusinessException(404, "商品不存在");
        }
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .set(Product::getStatus, ProductStatus.OFF_SALE));
        if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
            throw new IllegalStateException("商品搜索事件写入失败");
        }
        BusinessEventLog.committed("catalog", "product_admin_taken_down", "product", productId);
        return new ProductStatusVO(productId, ProductStatus.OFF_SALE.name());
    }
}
