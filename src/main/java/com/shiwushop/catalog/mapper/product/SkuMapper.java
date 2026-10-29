package com.shiwushop.catalog.mapper.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.catalog.entity.product.Sku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

public interface SkuMapper extends BaseMapper<Sku> {
    @Select("SELECT id, product_id, option_signature, price, stock, created_at, updated_at FROM skus WHERE id = #{skuId} FOR UPDATE")
    Sku selectForUpdate(@Param("skuId") Long skuId);
    @Update("""
            update skus
            set stock = stock - #{quantity}
            where id = #{skuId} and stock >= #{quantity} and #{quantity} > 0
            """)
    int decreaseStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    @Update("UPDATE skus SET stock = stock + #{quantity} WHERE id = #{skuId} AND #{quantity} > 0")
    int increaseStock(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    @Select("SELECT stock FROM skus WHERE id = #{skuId}")
    Integer selectStock(@Param("skuId") Long skuId);
}
