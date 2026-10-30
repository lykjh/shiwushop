package com.shiwushop.catalog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.catalog.vo.product.ProductOptionVO;
import com.shiwushop.catalog.vo.product.ProductOptionValueVO;
import com.shiwushop.catalog.vo.query.product.SkuOptionValueRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** 公开详情和商家详情共用选项装载规则，避免两边展示的规格不一致。 */
@Component
@RequiredArgsConstructor
class ProductOptionReader {
    private final ProductOptionMapper optionMapper;
    private final ProductOptionValueMapper valueMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    List<ProductOptionVO> loadOptions(Long productId) {
        List<ProductOption> options = optionMapper.selectList(
                new LambdaQueryWrapper<ProductOption>()
                        .eq(ProductOption::getProductId, productId)
                        .orderByAsc(ProductOption::getSortOrder, ProductOption::getId));
        if (options.isEmpty()) return List.of();
        Map<Long, List<ProductOptionValueVO>> values = new HashMap<>();
        for (ProductOptionValue value : valueMapper.selectList(
                new LambdaQueryWrapper<ProductOptionValue>()
                        .in(ProductOptionValue::getOptionId, options.stream().map(ProductOption::getId).toList())
                        .orderByAsc(ProductOptionValue::getSortOrder, ProductOptionValue::getId))) {
            values.computeIfAbsent(value.getOptionId(), ignored -> new ArrayList<>())
                    .add(new ProductOptionValueVO(value.getId(), value.getValueName()));
        }
        return options.stream().map(option -> new ProductOptionVO(
                option.getId(), option.getName(), values.getOrDefault(option.getId(), List.of()))).toList();
    }

    Map<Long, List<Long>> loadSkuValueIds(List<Sku> skus) {
        Map<Long, List<Long>> values = new HashMap<>();
        List<Long> ids = skus.stream().map(Sku::getId).toList();
        // 控制 IN 列表大小，详情包含大量 SKU 时仍按少量批次读取。
        for (int start = 0; start < ids.size(); start += 200) {
            for (SkuOptionValueRow row : skuOptionValueMapper.selectSkuValues(
                    ids.subList(start, Math.min(start + 200, ids.size())))) {
                values.computeIfAbsent(row.getSkuId(), ignored -> new ArrayList<>()).add(row.getValueId());
            }
        }
        return values;
    }

}
