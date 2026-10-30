package com.shiwushop.catalog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductImage;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.catalog.mapper.product.ProductImageMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.service.CategoryService;
import com.shiwushop.catalog.service.PublicProductService;
import com.shiwushop.catalog.vo.brand.BrandPublicVO;
import com.shiwushop.catalog.vo.category.CategoryDetailVO;
import com.shiwushop.catalog.vo.product.PublicProductDetailVO;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;
import com.shiwushop.catalog.vo.product.PublicProductVO;
import com.shiwushop.catalog.vo.query.product.PublicProductRow;
import com.shiwushop.catalog.vo.sku.PublicSkuVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.pagination.PageSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** 公开列表和详情只读；商家商品变更保留在 ProductServiceImpl。 */
@Service
@RequiredArgsConstructor
public class PublicProductServiceImpl implements PublicProductService {
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final ShopMapper shopMapper;
    private final BrandMapper brandMapper;
    private final ProductImageMapper productImageMapper;
    private final CategoryService categoryService;
    private final ProductOptionReader optionReader;

    /** 统一拒绝无效商品 ID，避免后续查询把参数错误误报成商品不存在。 */
    private void requireProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "商品ID必须大于0");
        }
    }
    /**
     * 校验公开筛选条件，多读一行判断是否还有下一页，再按商品 ID 返回游标。
     * 列表只组装公开卡片字段，详情和商家管理字段由独立接口读取。
     */
    @Override
    public PublicProductPageVO publicList(PublicProductQuery query) {
        validatePublicFilters(query);
        List<PublicProductRow> rows = selectPublicRows(query, query.size() + 1);
        boolean hasMore = rows.size() > query.size();
        List<PublicProductVO> items = rows.subList(0, Math.min(query.size(), rows.size()))
                .stream()
                .map(row -> new PublicProductVO(
                        row.getId(), row.getName(), row.getCoverMediaId(),
                        row.getBrandId() == null ? null
                                : new PublicProductVO.Brand(row.getBrandId(), row.getBrandName()),
                        new PublicProductVO.Shop(row.getShopId(), row.getShopName()),
                        row.getStartingPrice(), row.getSoldCount(), Boolean.TRUE.equals(row.getSoldOut())))
                .toList();
        return new PublicProductPageVO(items, query.size(), hasMore,
                hasMore ? items.getLast().id() : null);
    }

    /** 一次校验分页、游标、价格区间及可选分类、品牌、店铺筛选参数。 */
    private void validatePublicFilters(PublicProductQuery query) {
        PageSpec.of(1, query.size());
        if (query.afterId() != null && query.afterId() <= 0) {
            throw new BusinessException(400, "游标参数错误");
        }
        validatePriceRange(query.minPrice(), query.maxPrice());
        validatePublicCategory(query.categoryId());
        validatePublicBrand(query.brandId());
        validatePublicShop(query.shopId());
    }

    /** 拒绝负价格和最低价高于最高价的区间。 */
    private void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if ((minPrice != null && minPrice.signum() < 0)
                || (maxPrice != null && maxPrice.signum() < 0)
                || (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)) {
            throw new BusinessException(400, "价格区间错误");
        }
    }

    /** 确认筛选分类 ID 为正数，且分类及其祖先对外可见。 */
    private void validatePublicCategory(Long categoryId) {
        if (categoryId != null) {
            if (categoryId <= 0) {
                throw new BusinessException(400, "分类ID必须大于0");
            }
            categoryService.detail(categoryId);
        }
    }

    /** 确认可选品牌筛选参数为正数且品牌存在。 */
    private void validatePublicBrand(Long brandId) {
        if (brandId != null) {
            if (brandId <= 0) {
                throw new BusinessException(400, "品牌ID必须大于0");
            }
            if (brandMapper.selectById(brandId) == null) {
                throw new BusinessException(404, "品牌不存在");
            }
        }
    }

    /** 确认可选店铺筛选参数为正数且店铺存在。 */
    private void validatePublicShop(Long shopId) {
        if (shopId != null) {
            if (shopId <= 0) {
                throw new BusinessException(400, "店铺ID必须大于0");
            }
            if (shopMapper.selectById(shopId) == null) {
                throw new BusinessException(404, "店铺不存在");
            }
        }
    }

    /** 根据店铺、分类或品牌筛选选择专用 SQL，减少通用查询扫描无关商品。 */
    private List<PublicProductRow> selectPublicRows(PublicProductQuery query, int limit) {
        if (query.shopId() != null) {
            return productMapper.selectPublicShopPage(query.categoryId(), query.brandId(), query.shopId(),
                    query.minPrice(), query.maxPrice(), query.afterId(), limit);
        }
        if (query.categoryId() != null) {
            return productMapper.selectPublicFilteredPage(query.categoryId(), query.brandId(), query.shopId(),
                    query.minPrice(), query.maxPrice(),
                    query.afterId(), limit);
        }
        if (query.brandId() != null) {
            return productMapper.selectPublicBrandPage(null, query.brandId(), null,
                    query.minPrice(), query.maxPrice(),
                    query.afterId(), limit);
        }
        return productMapper.selectPublicPage(null, null, null,
                query.minPrice(), query.maxPrice(), query.afterId(), limit);
    }

    /**
     * 只展示在售且店铺启用的商品；分类隐藏时也拒绝详情访问。
     * 在同一只读事务中汇集图片、分类路径、品牌、销量、规格和 SKU 可售状态。
     */
    @Override
    @Transactional(readOnly = true)
    public PublicProductDetailVO publicDetail(Long id) {
        requireProductId(id);
        Product product = productMapper.selectById(id);
        if(product==null){
            throw new BusinessException(404, "商品不存在");
        }

        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(404, "商品不存在");
        }

        Shop shop=product.getShopId()==null?null:shopMapper.selectById(product.getShopId());
        if(shop==null||!Integer.valueOf(1).equals(shop.getEnabled())){
            throw new BusinessException(404, "店铺不存在");
        }

        PublicProductDetailVO vo = new PublicProductDetailVO();
        CategoryDetailVO categoryDetail=categoryService.detail(product.getCategoryId());
        vo.setId(product.getId());
        vo.setName(product.getName());
        vo.setDescription(product.getDescription());
        List<ProductImage> images = productImageMapper.selectList(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, product.getId())
                        .orderByAsc(ProductImage::getSortOrder));
        vo.setImageMediaIds(images.stream().map(ProductImage::getMediaId).toList());
        vo.setCoverMediaId(images.stream()
                .filter(image -> Integer.valueOf(1).equals(image.getIsCover()))
                .map(ProductImage::getMediaId).findFirst().orElse(null));
        vo.setCategoryPath(categoryDetail.getPath());
        vo.setShop(new PublicProductVO.Shop(shop.getId(), shop.getName()));
        Long brandId = productMapper.selectBrandId(id);
        if (brandId != null) {
            Brand brand = brandMapper.selectById(brandId);
            vo.setBrand(new BrandPublicVO(brand.getId(), brand.getName(), brand.getLogoMediaId()));
        }
        vo.setSoldCount(Objects.requireNonNullElse(productMapper.getSoldCount(id), 0L));


        vo.setOptions(optionReader.loadOptions(id));

        List<Sku> skuRows=skuMapper.selectList(
                new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getProductId, id)
                        .orderByAsc(Sku::getId)
        );
        Map<Long, List<Long>> skuValues = optionReader.loadSkuValueIds(skuRows);
        List<PublicSkuVO> skus=skuRows.stream().map(sku -> {
            PublicSkuVO skuVO = new PublicSkuVO();
            skuVO.setId(sku.getId());
            skuVO.setOptionValueIds(skuValues.getOrDefault(sku.getId(), List.of()));
            skuVO.setPrice(sku.getPrice());
            skuVO.setAvailable(sku.getStock() != null && sku.getStock() > 0);
            return skuVO;
        }).toList();

        vo.setSkus(skus);
        return vo;
    }

}
