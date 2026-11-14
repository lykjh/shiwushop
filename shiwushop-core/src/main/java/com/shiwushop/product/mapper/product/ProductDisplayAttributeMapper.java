package com.shiwushop.product.mapper.product;

import com.shiwushop.product.entity.product.ProductDisplayAttribute;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ProductDisplayAttributeMapper {
    @Select("SELECT product_id, attribute_key, attribute_value, position FROM product_display_attributes WHERE product_id=#{productId} ORDER BY position, attribute_key")
    List<ProductDisplayAttribute> list(@Param("productId") Long productId);

    @Delete("DELETE FROM product_display_attributes WHERE product_id=#{productId}")
    int delete(@Param("productId") Long productId);

    @Insert("INSERT INTO product_display_attributes(product_id,attribute_key,attribute_value,position) VALUES(#{productId},#{attributeKey},#{attributeValue},#{position})")
    int insert(ProductDisplayAttribute attribute);
}
