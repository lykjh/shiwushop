package com.shiwushop.merchant.mapper.application;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.merchant.entity.application.MerchantApplication;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MerchantApplicationMapper extends BaseMapper<MerchantApplication> {
    @Select("SELECT * FROM merchant_applications WHERE id = #{id} FOR UPDATE")
    MerchantApplication selectForUpdate(@Param("id") Long id);
}
