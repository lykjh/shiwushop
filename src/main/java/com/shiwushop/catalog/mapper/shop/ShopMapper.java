package com.shiwushop.catalog.mapper.shop;

import com.shiwushop.catalog.vo.category.CategoryPathItemVO;
import com.shiwushop.catalog.vo.shop.ShopPublicVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.catalog.entity.shop.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ShopMapper extends BaseMapper<Shop> {
    @Select("SELECT * FROM shops WHERE id = #{shopId} FOR UPDATE")
    Shop selectForUpdate(@Param("shopId") Long shopId);
    List<ShopPublicVO> selectPublicPage(@Param("categoryId") Long categoryId,
                                        @Param("size") int size,
                                        @Param("offset") long offset);

    long countPublic(@Param("categoryId") Long categoryId);

    List<CategoryPathItemVO> selectPublicCategories(@Param("shopId") Long shopId);
}
