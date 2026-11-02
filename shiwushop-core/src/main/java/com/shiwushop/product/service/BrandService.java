package com.shiwushop.product.service;

import com.shiwushop.product.dto.brand.BrandSaveDTO;
import com.shiwushop.product.dto.brand.BrandCreateDTO;
import com.shiwushop.product.vo.brand.BrandPublicVO;
import com.shiwushop.product.vo.brand.AdminBrandListVO;
import com.shiwushop.product.vo.brand.BrandVO;

import java.util.List;

public interface BrandService {
    /** 管理员创建品牌，校验可选的品牌图片并写入搜索刷新事件。 */
    AdminBrandListVO create(BrandCreateDTO dto, Long adminId);

    /** 返回管理员使用的品牌管理列表。 */
    List<AdminBrandListVO> adminList();

    /** 管理员更新品牌资料；服务层处理关联校验与搜索刷新。 */
    BrandVO update(Long id, BrandSaveDTO dto, Long adminId);

    /** 返回公开品牌，可按商品分类筛选。 */
    List<BrandPublicVO> publicList(Long categoryId);
}
