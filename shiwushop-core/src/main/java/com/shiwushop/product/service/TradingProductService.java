package com.shiwushop.product.service;

import com.shiwushop.product.entity.product.Product;
import com.shiwushop.product.entity.product.Sku;

import java.util.List;
import java.util.Map;

/** 交易使用的商品快照、规格和可售性；商品表查询留在商品模块。 */
public interface TradingProductService {
    Sku findSku(Long skuId);

    Sku lockSku(Long skuId);

    Product findProduct(Long productId);

    /** 调用方已在事务中锁住店铺；商品当前读须先于 SKU 行锁。 */
    Product requireMarketableProductForUpdate(Long shopId, Long productId);

    List<String> optionNames(Long skuId);

    String unavailableReason(Sku sku, Product product, int quantity);

    /** 批量装载商品、店铺、分类祖先及规格，不按购物车项逐条回源。 */
    Map<Long, CartProduct> cartProducts(Map<Long, Integer> quantities);

    record CartProduct(Sku sku, Product product, List<String> optionNames, String unavailableReason) {
    }
}
