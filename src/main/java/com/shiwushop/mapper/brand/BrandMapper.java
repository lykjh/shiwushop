package com.shiwushop.mapper.brand;

import com.shiwushop.vo.brand.BrandPublicVO;
import com.shiwushop.vo.brand.AdminBrandListVO;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shiwushop.entity.brand.Brand;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BrandMapper extends BaseMapper<Brand> {
    @Select("SELECT id, name, logo_media_id AS logoMediaId, enabled FROM brands ORDER BY id")
    List<AdminBrandListVO> selectAdminList();

    List<BrandPublicVO> selectPublicByCategory(@Param("categoryId") Long categoryId);
}
