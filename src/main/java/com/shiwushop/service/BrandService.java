package com.shiwushop.service;

import com.shiwushop.dto.brand.BrandSaveDTO;
import com.shiwushop.dto.brand.BrandCreateDTO;
import com.shiwushop.vo.brand.BrandPublicVO;
import com.shiwushop.vo.brand.AdminBrandListVO;
import com.shiwushop.vo.brand.BrandVO;

import java.util.List;

public interface BrandService {
    AdminBrandListVO create(BrandCreateDTO dto, Long adminId);

    List<AdminBrandListVO> adminList();

    BrandVO update(Long id, BrandSaveDTO dto, Long adminId);

    List<BrandPublicVO> publicList(Long categoryId);
}
