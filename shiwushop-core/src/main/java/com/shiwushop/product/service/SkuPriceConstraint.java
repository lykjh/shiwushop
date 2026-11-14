package com.shiwushop.product.service;

import java.math.BigDecimal;

/** 商品变价事务持有SKU锁后，同步核对已存在的价格承诺。 */
public interface SkuPriceConstraint {
    void check(Long skuId, BigDecimal newPrice);
}
