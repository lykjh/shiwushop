package com.shiwushop.product.mapper.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.product.entity.product.ProductListingSubmission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ProductListingSubmissionMapper extends BaseMapper<ProductListingSubmission> {
    @Select("SELECT * FROM product_listing_submissions WHERE product_id=#{productId} AND revision=#{revision} FOR UPDATE")
    ProductListingSubmission selectForUpdate(@Param("productId") Long productId, @Param("revision") Long revision);
}
