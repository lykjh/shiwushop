package com.shiwushop.catalog.mapper.product;

import com.shiwushop.catalog.vo.query.product.SkuOptionValueRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SkuOptionValueMapper {
    @Insert("INSERT INTO sku_option_values (sku_id, option_value_id) VALUES (#{skuId}, #{valueId})")
    int insertLink(@Param("skuId") Long skuId, @Param("valueId") Long valueId);

    List<Long> selectValueIds(@Param("skuId") Long skuId);

    List<SkuOptionValueRow> selectSkuValues(@Param("skuIds") List<Long> skuIds);
}
