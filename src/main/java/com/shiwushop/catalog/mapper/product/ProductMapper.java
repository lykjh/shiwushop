package com.shiwushop.catalog.mapper.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.vo.query.product.PublicProductRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;

import java.math.BigDecimal;
import java.util.List;

public interface ProductMapper extends BaseMapper<Product> {
    @Insert("INSERT INTO product_brands(product_id, brand_id) VALUES (#{productId}, #{brandId})")
    int insertBrand(@Param("productId") Long productId, @Param("brandId") Long brandId);

    @Delete("DELETE FROM product_brands WHERE product_id = #{productId}")
    int deleteBrand(@Param("productId") Long productId);

    @Select("SELECT brand_id FROM product_brands WHERE product_id = #{productId}")
    Long selectBrandId(@Param("productId") Long productId);

    @Select("SELECT id, shop_id, category_id, name, description, status, created_at, updated_at FROM products WHERE id = #{id} FOR UPDATE")
    Product selectForUpdate(@Param("id") Long id);
    List<PublicProductRow> selectPublicPage(
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("shopId") Long shopId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<PublicProductRow> selectPublicShopPage(
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("shopId") Long shopId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<PublicProductRow> selectPublicFilteredPage(
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("shopId") Long shopId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<PublicProductRow> selectPublicBrandPage(
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("shopId") Long shopId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    List<PublicProductRow> selectPublicCardsByIds(
            @Param("ids") List<Long> ids,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("shopId") Long shopId,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice);



    @Select("SELECT COALESCE(sold_count, 0) FROM product_sales_stats WHERE product_id = #{productId}")
    Long getSoldCount(@Param("productId") Long productId);

    @Insert("""
            INSERT INTO product_sales_stats (product_id, sold_count) VALUES (#{productId}, #{quantity})
            ON DUPLICATE KEY UPDATE sold_count = sold_count + VALUES(sold_count)
            """)
    int increaseSoldCount(@Param("productId") Long productId, @Param("quantity") int quantity);
}
