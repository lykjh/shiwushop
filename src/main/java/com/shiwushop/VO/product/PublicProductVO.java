package com.shiwushop.vo.product;

import com.shiwushop.entity.brand.Brand;
import com.shiwushop.entity.shop.Shop;

import java.math.BigDecimal;

public record PublicProductVO(
        Long id, String name, Long coverMediaId,
        Brand brand, Shop shop,
        BigDecimal startingPrice, Long soldCount, boolean soldOut) {

    public record Brand(Long id, String name) {}
    public record Shop(Long id, String name) {}
}
