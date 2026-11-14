package com.shiwushop.merchant.mapper.shop;

import com.shiwushop.merchant.vo.shop.ShopCategoryVO;
import com.shiwushop.merchant.vo.shop.ShopPublicVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.merchant.entity.shop.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;

public interface ShopMapper extends BaseMapper<Shop> {
    @Select("SELECT * FROM shops WHERE id = #{shopId} FOR UPDATE")
    Shop selectForUpdate(@Param("shopId") Long shopId);

    @Select("SELECT id FROM shops WHERE enabled=1 AND qualification_valid_until < #{today} " +
            "ORDER BY qualification_valid_until,id LIMIT 1000")
    List<Long> selectExpiredQualificationIds(@Param("today") LocalDate today);

    List<ShopPublicVO> selectPublicPage(@Param("categoryId") Long categoryId,
                                        @Param("size") int size,
                                        @Param("offset") long offset);

    long countPublic(@Param("categoryId") Long categoryId);

    List<ShopCategoryVO> selectPublicCategories(@Param("shopId") Long shopId);
}
