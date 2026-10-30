package com.shiwushop.catalog.service.impl;

import com.shiwushop.catalog.dto.brand.BrandSaveDTO;
import com.shiwushop.catalog.dto.brand.BrandCreateDTO;
import com.shiwushop.catalog.vo.brand.BrandPublicVO;
import com.shiwushop.catalog.vo.brand.AdminBrandListVO;
import com.shiwushop.catalog.vo.brand.BrandVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.catalog.service.BrandService;
import com.shiwushop.catalog.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandMapper brandMapper;
    private final MediaAssetAccess mediaAssetAccess;
    private final CategoryService categoryService;
    private final OutboxEventMapper outboxEventMapper;

    /**
     * 校验品牌标识图片属于操作人，写入品牌并在同一事务写入搜索刷新事件。
     * 品牌名称并发冲突由数据库唯一约束转换为业务 409。
     */
    @Override
    @Transactional
    public AdminBrandListVO create(BrandCreateDTO dto, Long adminId) {
        if (dto.getLogoMediaId() != null) {
            mediaAssetAccess.requireReadyOwned(dto.getLogoMediaId(), adminId, "品牌标识媒体不存在或不可用");
        }
        Brand brand = new Brand();
        brand.setName(dto.getName().strip());
        brand.setLogoMediaId(dto.getLogoMediaId());
        brand.setEnabled(dto.getEnabled());

        try {
            brandMapper.insert(brand);
            // 数据变更与搜索刷新事件同事务提交，避免索引同步时漏掉品牌。
            outboxEventMapper.insertSearchBrandRefresh(brand.getId());
        }catch (DuplicateKeyException e) {
            throw new BusinessException(409, "品牌名称已存在", e);
        }

        AdminBrandListVO vo = new AdminBrandListVO();
        vo.setId(brand.getId());
        vo.setName(brand.getName());
        vo.setLogoMediaId(brand.getLogoMediaId());
        vo.setEnabled(brand.getEnabled());
        return vo;
    }


    /** 返回品牌管理列表，包括停用的品牌。 */
    @Override
    public List<AdminBrandListVO> adminList() {
        return brandMapper.selectAdminList();
    }

    /** 修改品牌名称、图片和启用状态，并在事务内通知搜索索引刷新。 */
    @Override
    @Transactional
    public BrandVO update(Long id, BrandSaveDTO dto, Long adminId) {
        if(brandMapper.selectById(id)==null){
            throw new BusinessException(404, "品牌不存在");
        }
        if (dto.getLogoMediaId() != null) {
            mediaAssetAccess.requireReadyOwned(dto.getLogoMediaId(), adminId, "品牌标识媒体不存在或不可用");
        }

        try {
            brandMapper.update(null,new LambdaUpdateWrapper<Brand>()
                    .eq(Brand::getId, id)
                    .set(Brand::getName, dto.getName().strip())
                    .set(Brand::getLogoMediaId, dto.getLogoMediaId())
                    .set(Brand::getEnabled, dto.getEnabled()));
        }catch (DuplicateKeyException e){
            throw new BusinessException(409, "品牌名称已存在", e);
        }
        outboxEventMapper.insertSearchBrandRefresh(id);
        return toVO(brandMapper.selectById(id));
    }

    /** 列出启用中的品牌；指定分类时先确认分类可见，再按分类查询品牌。 */
    @Override
    public List<BrandPublicVO> publicList(Long categoryId) {
        if(categoryId==null){
            return brandMapper.selectList(
                    new LambdaQueryWrapper<Brand>()
                            .eq(Brand::getEnabled, 1)
                            .orderByAsc(Brand::getId))
                .stream()
                .map(brand -> new BrandPublicVO(
                        brand.getId(),
                        brand.getName(),
                        brand.getLogoMediaId()
                ))
                .toList();
        }

        if(categoryId<=0){
            throw new BusinessException(400, "分类ID必须大于0");
        }

        categoryService.detail(categoryId);
        return brandMapper.selectPublicByCategory(categoryId);
    }

    /** 将品牌实体转换为管理接口返回的简要视图。 */
    private BrandVO toVO(Brand brand) {
        return new BrandVO(
                brand.getId(),
                brand.getName(),
                brand.getLogoMediaId(),
                brand.getEnabled()
        );
    }

}
