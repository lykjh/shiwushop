package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.brand.BrandSaveDTO;
import com.shiwushop.catalog.dto.brand.BrandCreateDTO;
import com.shiwushop.catalog.vo.brand.BrandPublicVO;
import com.shiwushop.catalog.vo.brand.AdminBrandListVO;
import com.shiwushop.catalog.vo.brand.BrandVO;

import java.util.List;

public interface BrandService {
    AdminBrandListVO create(BrandCreateDTO dto, Long adminId);

    List<AdminBrandListVO> adminList();

    BrandVO update(Long id, BrandSaveDTO dto, Long adminId);

    List<BrandPublicVO> publicList(Long categoryId);
}
