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
    List<String> optionNames(Long skuId);
    String unavailableReason(Sku sku, Product product, int quantity);
    /** 批量装载商品、店铺、分类祖先及规格，不按购物车项逐条回源。 */
    Map<Long, CartProduct> cartProducts(Map<Long, Integer> quantities);
    record CartProduct(Sku sku, Product product, List<String> optionNames, String unavailableReason) {}
}
