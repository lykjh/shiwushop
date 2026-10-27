package com.shiwushop.mapper.shop;

import com.shiwushop.vo.category.CategoryPathItemVO;
import com.shiwushop.vo.shop.ShopPublicVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.shop.Shop;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ShopMapper extends BaseMapper<Shop> {
    List<ShopPublicVO> selectPublicPage(@Param("categoryId") Long categoryId,
                                        @Param("size") int size,
                                        @Param("offset") long offset);

    long countPublic(@Param("categoryId") Long categoryId);

    List<CategoryPathItemVO> selectPublicCategories(@Param("shopId") Long shopId);
}
