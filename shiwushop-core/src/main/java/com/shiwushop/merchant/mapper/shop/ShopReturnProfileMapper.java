package com.shiwushop.merchant.mapper.shop;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.merchant.entity.shop.ShopReturnProfile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ShopReturnProfileMapper extends BaseMapper<ShopReturnProfile> {
    @Select("SELECT * FROM shop_return_profiles WHERE shop_id = #{shopId} FOR UPDATE")
    ShopReturnProfile selectForUpdate(@Param("shopId") Long shopId);
}
