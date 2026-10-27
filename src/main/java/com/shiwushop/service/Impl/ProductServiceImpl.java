package com.shiwushop.service.impl;

import com.shiwushop.entity.brand.Brand;
import com.shiwushop.entity.product.Product;
import com.shiwushop.entity.product.ProductImage;
import com.shiwushop.entity.product.ProductOption;
import com.shiwushop.entity.product.ProductOptionValue;
import com.shiwushop.entity.product.Sku;
import com.shiwushop.entity.shop.Shop;
import com.shiwushop.mapper.brand.BrandMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.product.ProductImageMapper;
import com.shiwushop.mapper.product.ProductMapper;
import com.shiwushop.mapper.product.ProductOptionMapper;
import com.shiwushop.mapper.product.ProductOptionValueMapper;
import com.shiwushop.mapper.product.SkuMapper;
import com.shiwushop.mapper.product.SkuOptionValueMapper;
import com.shiwushop.mapper.shop.ShopMapper;

import com.shiwushop.dto.product.MerchantProductCreateDTO;
import com.shiwushop.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.dto.product.ProductOptionSaveDTO;
import com.shiwushop.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.dto.product.PublicProductQuery;
import com.shiwushop.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.vo.brand.BrandPublicVO;
import com.shiwushop.vo.category.CategoryDetailVO;
import com.shiwushop.vo.product.MerchantProductDetailVO;
import com.shiwushop.vo.product.MerchantProductPageVO;
import com.shiwushop.vo.product.MerchantProductVO;
import com.shiwushop.vo.product.ProductOptionVO;
import com.shiwushop.vo.product.ProductOptionValueVO;
import com.shiwushop.vo.product.ProductStatusVO;
import com.shiwushop.vo.product.PublicProductDetailVO;
import com.shiwushop.vo.product.PublicProductPageVO;
import com.shiwushop.vo.product.PublicProductVO;
import com.shiwushop.vo.sku.MerchantSkuVO;
import com.shiwushop.vo.sku.PublicSkuVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.MerchantShopAccess;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.util.PageSpec;
import com.shiwushop.common.auth.MediaAssetAccess;
import com.shiwushop.entity.status.ProductStatus;
import com.shiwushop.vo.query.product.PublicProductRow;
import com.shiwushop.vo.query.product.SkuOptionValueRow;
import com.shiwushop.service.CategoryService;
import com.shiwushop.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final ShopMapper shopMapper;
    private final MerchantShopAccess merchantShopAccess;
    private final MediaAssetAccess mediaAssetAccess;
    private final BrandMapper brandMapper;
    private final ProductImageMapper productImageMapper;
    private final CategoryService categoryService;
    private final ProductOptionMapper optionMapper;
    private final ProductOptionValueMapper valueMapper;
    private final SkuOptionValueMapper skuOptionValueMapper;
    private final OutboxEventMapper outboxEventMapper;

    private void queueSearchRefresh(Long productId) {
        if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
            throw new IllegalStateException("商品搜索事件写入失败");
        }
    }

    private void requireProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "商品ID必须大于0");
        }
    }

    private OwnedProduct requireOwnedProduct(Long userId, Long productId, boolean lock) {
        Product product = lock ? productMapper.selectForUpdate(productId) : productMapper.selectById(productId);
        if (product == null || product.getShopId() == null) {
            throw new BusinessException(404, "商品不存在");
        }
        Shop shop = merchantShopAccess.requireOwned(userId, product.getShopId(), "商品不存在");
        return new OwnedProduct(product, shop);
    }

    private record OwnedProduct(Product product, Shop shop) { }

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

    private void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if ((minPrice != null && minPrice.signum() < 0)
                || (maxPrice != null && maxPrice.signum() < 0)
                || (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)) {
            throw new BusinessException(400, "价格区间错误");
        }
    }

    private void validatePublicCategory(Long categoryId) {
        if (categoryId != null) {
            if (categoryId <= 0) {
                throw new BusinessException(400, "分类ID必须大于0");
            }
            categoryService.detail(categoryId);
        }
    }

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


        vo.setOptions(loadOptions(id));

        List<Sku> skuRows=skuMapper.selectList(
                new LambdaQueryWrapper<Sku>()
                        .eq(Sku::getProductId, id)
                        .orderByAsc(Sku::getId)
        );
        Map<Long, List<Long>> skuValues = loadSkuValueIds(skuRows);
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

    @Override
    @Transactional
    public MerchantProductVO createDraft(Long userId, MerchantProductCreateDTO dto) {
        SessionUser.requireId(userId);

        Shop shop = merchantShopAccess.requireOwned(userId, dto.getShopId(), "店铺不存在");

        validateCategoryAndBrand(dto.getCategoryId(), dto.getBrandId());

        List<Long> imageIds = dto.getImageMediaIds() == null ? List.of() : dto.getImageMediaIds();
        validateImages(imageIds, dto.getCoverMediaId());
        validateOwnedMedia(imageIds, userId);

        Product product = new Product();
        product.setShopId(shop.getId());
        product.setCategoryId(dto.getCategoryId());
        product.setBrandId(dto.getBrandId());
        product.setName(dto.getName().strip());
        product.setDescription(dto.getDescription());
        product.setStatus(ProductStatus.DRAFT);
        productMapper.insert(product);
        if (dto.getBrandId() != null) {
            productMapper.insertBrand(product.getId(), dto.getBrandId());
        }

        insertProductImages(product.getId(), imageIds, dto.getCoverMediaId());
        queueSearchRefresh(product.getId());
        return new MerchantProductVO(
                product.getId(),
                product.getShopId(),
                product.getCategoryId(),
                dto.getBrandId(),
                product.getName(),
                product.getStatus().name()
        );
    }

    @Override
    @Transactional
    public MerchantProductVO updateMerchantProduct(Long userId, Long productId, MerchantProductUpdateDTO dto) {
            SessionUser.requireId(userId);
            requireProductId(productId);

            Product product = requireOwnedProduct(userId, productId, true).product();

            validateCategoryAndBrand(dto.getCategoryId(), dto.getBrandId());

            List<ProductImage> oldImages = productImageMapper.selectList(
                    new LambdaQueryWrapper<ProductImage>()
                            .eq(ProductImage::getProductId, productId)
                            .orderByAsc(ProductImage::getSortOrder));
            List<Long> imageIds = dto.getImageMediaIds() == null
                    ? oldImages.stream().map(ProductImage::getMediaId).toList()
                    : dto.getImageMediaIds();
            Long coverId = resolveCoverId(dto, oldImages);
            validateImages(imageIds, coverId);
            if (product.getStatus() == ProductStatus.ON_SALE && (imageIds.isEmpty() || coverId == null)) {
                throw new BusinessException(409, "在售商品不能缺少封面和详情图片");
            }
            if (dto.getImageMediaIds() != null) {
                validateOwnedMedia(imageIds, userId);
            }

            productMapper.update(null, new LambdaUpdateWrapper<Product>()
                    .eq(Product::getId, productId)
                    .set(Product::getCategoryId, dto.getCategoryId())
                    .set(Product::getName, dto.getName().strip())
                    .set(Product::getDescription, dto.getDescription()));
            productMapper.deleteBrand(productId);
            if (dto.getBrandId() != null) {
                productMapper.insertBrand(productId, dto.getBrandId());
            }

            updateProductImages(productId, dto, imageIds, coverId);
            queueSearchRefresh(productId);

            Product updated = productMapper.selectById(productId);
            return new MerchantProductVO(
                    updated.getId(),
                    updated.getShopId(),
                    updated.getCategoryId(),
                    dto.getBrandId(),
                    updated.getName(),
                    updated.getStatus().name()
            );
    }

    private void validateCategoryAndBrand(Long categoryId, Long brandId) {
        if (categoryService.detail(categoryId).getLevel() < 2) {
            throw new BusinessException(409, "商品只能选择二级或三级分类");
        }
        if (brandId == null) {
            return;
        }
        Brand brand = brandMapper.selectById(brandId);
        if (brand == null) {
            throw new BusinessException(404, "品牌不存在");
        }
        if (!Integer.valueOf(1).equals(brand.getEnabled())) {
            throw new BusinessException(409, "品牌已停用");
        }
    }

    private void validateImages(List<Long> imageIds, Long coverId) {
        if (new HashSet<>(imageIds).size() != imageIds.size()) {
            throw new BusinessException(400, "商品图片不能重复");
        }
        if (coverId != null && !imageIds.contains(coverId)) {
            throw new BusinessException(400, "封面必须包含在商品图片中");
        }
    }

    private void validateOwnedMedia(List<Long> imageIds, Long userId) {
        for (Long mediaId : imageIds) {
            mediaAssetAccess.requireReadyOwned(mediaId, userId, "商品图片不存在或不可用");
        }
    }

    private Long resolveCoverId(MerchantProductUpdateDTO dto, List<ProductImage> oldImages) {
        if (dto.getCoverMediaId() != null) {
            return dto.getCoverMediaId();
        }
        if (dto.getImageMediaIds() != null) {
            return null;
        }
        return oldImages.stream().filter(image -> Integer.valueOf(1).equals(image.getIsCover()))
                .map(ProductImage::getMediaId).findFirst().orElse(null);
    }

    private void insertProductImages(Long productId, List<Long> imageIds, Long coverId) {
        for (int i = 0; i < imageIds.size(); i++) {
            ProductImage image = new ProductImage();
            image.setProductId(productId);
            image.setMediaId(imageIds.get(i));
            image.setIsCover(imageIds.get(i).equals(coverId) ? 1 : 0);
            image.setSortOrder(i);
            productImageMapper.insert(image);
        }
    }

    private void updateProductImages(Long productId, MerchantProductUpdateDTO dto,
                                     List<Long> imageIds, Long coverId) {
        if (dto.getImageMediaIds() != null) {
            productImageMapper.delete(new LambdaQueryWrapper<ProductImage>()
                    .eq(ProductImage::getProductId, productId));
            insertProductImages(productId, imageIds, coverId);
        } else if (dto.getCoverMediaId() != null) {
            productImageMapper.update(null, new LambdaUpdateWrapper<ProductImage>()
                    .eq(ProductImage::getProductId, productId)
                    .set(ProductImage::getIsCover, 0));
            productImageMapper.update(null, new LambdaUpdateWrapper<ProductImage>()
                    .eq(ProductImage::getProductId, productId)
                    .eq(ProductImage::getMediaId, coverId)
                    .set(ProductImage::getIsCover, 1));
        }
    }

    @Override
    @Transactional
    public List<ProductOptionVO> replaceOptions(Long userId, Long productId, ProductOptionsUpdateDTO dto) {
        SessionUser.requireId(userId);
        requireProductId(productId);

        Product product = requireOwnedProduct(userId, productId, true).product();

        // 已有 SKU 时替换规格会使既有 SKU 签名和历史订单含义发生变化。
        if (product.getStatus() == ProductStatus.ON_SALE
                || skuMapper.selectCount(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, productId)) > 0) {
            throw new BusinessException(409, "商品已有SKU，不能替换规格项");
        }

        validateOptions(dto.getOptions());

        try {
            deleteProductOptions(productId);
            return insertProductOptions(productId, dto.getOptions());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "规格重复或已被SKU引用",e);
        }
    }

    private void validateOptions(List<ProductOptionSaveDTO> options) {
        Set<String> names = new HashSet<>();
        for (ProductOptionSaveDTO option : options) {
            if (!names.add(option.getName().strip())) {
                throw new BusinessException(409, "规格项名称不能重复");
            }
            Set<String> values = new HashSet<>();
            for (String value : option.getValues()) {
                if (!values.add(value.strip())) {
                    throw new BusinessException(409, "同一规格项的值重复");
                }
            }
        }
    }

    private void deleteProductOptions(Long productId) {
        List<ProductOption> oldOptions = optionMapper.selectList(new LambdaQueryWrapper<ProductOption>()
                .eq(ProductOption::getProductId, productId));
        if (oldOptions.isEmpty()) {
            return;
        }
        valueMapper.delete(new LambdaQueryWrapper<ProductOptionValue>()
                .in(ProductOptionValue::getOptionId, oldOptions.stream().map(ProductOption::getId).toList()));
        optionMapper.delete(new LambdaQueryWrapper<ProductOption>()
                .eq(ProductOption::getProductId, productId));
    }

    private List<ProductOptionVO> insertProductOptions(Long productId, List<ProductOptionSaveDTO> inputs) {
        List<ProductOptionVO> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ProductOptionSaveDTO input = inputs.get(i);
            ProductOption option = new ProductOption();
            option.setProductId(productId);
            option.setName(input.getName().strip());
            option.setSortOrder(i);
            optionMapper.insert(option);
            List<ProductOptionValueVO> values = new ArrayList<>();
            for (int j = 0; j < input.getValues().size(); j++) {
                ProductOptionValue value = new ProductOptionValue();
                value.setOptionId(option.getId());
                value.setValueName(input.getValues().get(j).strip());
                value.setSortOrder(j);
                valueMapper.insert(value);
                values.add(new ProductOptionValueVO(value.getId(), value.getValueName()));
            }
            result.add(new ProductOptionVO(option.getId(), option.getName(), values));
        }
        return result;
    }

    @Override
    @Transactional
    public MerchantSkuVO updateMerchantSku(Long userId, Long productId, Long skuId, MerchantSkuUpdateDTO dto) {
        SessionUser.requireId(userId);
        if (productId == null || productId <= 0 || skuId == null || skuId <= 0) {
            throw new BusinessException(400, "ID必须大于0");
        }

        requireOwnedProduct(userId, productId, false);

        Sku sku = skuMapper.selectForUpdate(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new BusinessException(404, "SKU不存在");
        }

        skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getId, skuId)
                .set(Sku::getPrice, dto.getPrice())
                .set(Sku::getStock, dto.getStock()));
        queueSearchRefresh(productId);

        return new MerchantSkuVO(
                skuId,
                productId,
                skuOptionValueMapper.selectValueIds(skuId),
                dto.getPrice(),
                dto.getStock(),
                false);
    }

    @Override
    @Transactional
    public MerchantSkuVO createMerchantSku(Long userId, Long productId, MerchantSkuCreateDTO dto) {

        SessionUser.requireId(userId);
        requireProductId(productId);

        requireOwnedProduct(userId, productId, true);

        List<Long> orderedIds = validatedSkuValueIds(productId, dto.getOptionValueIds());
        String combination = String.join(",", orderedIds.stream().map(String::valueOf).toList());
        String signature;
        try {
            signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(combination.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }

        Sku sku = new Sku();
        sku.setProductId(productId);
        sku.setOptionSignature(signature);
        sku.setPrice(dto.getPrice());
        sku.setStock(dto.getStock());

        try {
            skuMapper.insert(sku);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "规格组合已存在", e);
        }

        for (Long valueId : orderedIds) {
            skuOptionValueMapper.insertLink(sku.getId(), valueId);
        }
        queueSearchRefresh(productId);
        return new MerchantSkuVO(
                sku.getId(), productId, orderedIds, dto.getPrice(), dto.getStock(), false);
    }

    private List<Long> validatedSkuValueIds(Long productId, List<Long> valueIds) {
        List<ProductOption> options=optionMapper.selectList(
                new LambdaQueryWrapper<ProductOption>()
                .eq(ProductOption::getProductId, productId)
                        .orderByAsc(ProductOption::getSortOrder,ProductOption::getId)
        );
        if(options.isEmpty()||valueIds.size()!=options.size()){
            throw new BusinessException(400, "规格项数量不匹配");
        }

        List<ProductOptionValue> selected =valueMapper.selectList(
                new LambdaQueryWrapper<ProductOptionValue>()
                        .in(ProductOptionValue::getId,valueIds)
        );

        if(selected.size()!=valueIds.size()){
            throw new BusinessException(400, "规格值不存在或重复");
        }

        Map<Long, ProductOptionValue> chosen = new HashMap<>();
        for(ProductOptionValue value : selected){
            if(chosen.putIfAbsent(value.getOptionId(),value) != null){
                throw new BusinessException(400, "同一规格项不能选两个值");
            }
        }

        List<Long> orderedIds = new ArrayList<>();
        List<String> names=new ArrayList<>();
        for(ProductOption option : options){
            ProductOptionValue value=chosen.get(option.getId());
            if(value == null){
                throw new BusinessException(400, "规格值不属于该商品或有规格项未选择");
            }
            orderedIds.add(value.getId());
            names.add(value.getValueName());
        }

        String spec = String.join("/", names);
        if(spec.length()>255){
            throw new BusinessException(400, "规格项名称或值过长");
        }
        return orderedIds;
    }

    @Override
    @Transactional
    public ProductStatusVO updateMerchantProductStatus(Long userId, Long productId, ProductStatusUpdateDTO dto) {
        SessionUser.requireId(userId);
        requireProductId(productId);

        OwnedProduct owned = requireOwnedProduct(userId, productId, true);
        Product product = owned.product();
        Shop shop = owned.shop();

        String requestedStatus = dto == null ? null : dto.getStatus();
        if (!ProductStatus.ON_SALE.name().equals(requestedStatus)
                && !ProductStatus.OFF_SALE.name().equals(requestedStatus)) {
            throw new BusinessException(400, "商品状态错误");
        }
        ProductStatus status = ProductStatus.valueOf(requestedStatus);
        if (status == ProductStatus.ON_SALE) {
            validateReadyForSale(product, shop);
        }

        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .set(Product::getStatus, status));
        queueSearchRefresh(productId);

        return new ProductStatusVO(productId, status.name());
    }

    /** 上架前同时校验店铺、分类、品牌、图片和 SKU，避免公开不可购买的商品。 */
    private void validateReadyForSale(Product product, Shop shop) {
        Long productId = product.getId();
        if (!Integer.valueOf(1).equals(shop.getEnabled())) {
            throw new BusinessException(409, "店铺已暂停营业");
        }
        if (categoryService.detail(product.getCategoryId()).getLevel() < 2) {
            throw new BusinessException(409, "商品不能归属一级分类");
        }
        Long brandId = productMapper.selectBrandId(productId);
        if (brandId != null) {
            Brand brand = brandMapper.selectById(brandId);
            if (brand == null || !Integer.valueOf(1).equals(brand.getEnabled())) {
                throw new BusinessException(409, "品牌不可用");
            }
        }
        if (product.getName() == null || product.getName().isBlank()
                || productImageMapper.selectCount(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, productId)) == 0
                || productImageMapper.selectCount(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, productId).eq(ProductImage::getIsCover, 1)) != 1) {
            throw new BusinessException(409, "商品资料不完整");
        }
        if (skuMapper.selectCount(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getProductId, productId)
                .isNotNull(Sku::getOptionSignature)
                .ne(Sku::getOptionSignature, "")
                .gt(Sku::getPrice, BigDecimal.ZERO)
                .ge(Sku::getStock, 0)) == 0) {
            throw new BusinessException(409, "商品缺少有效SKU");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantProductPageVO listMerchantProducts(Long userId, Long shopId, int page, int size) {
        SessionUser.requireId(userId);
        if (shopId == null || shopId <= 0) {
            throw new BusinessException(400, "店铺或分页参数错误");
        }
        PageSpec pageSpec = PageSpec.of(page, size, "店铺或分页参数错误");

        merchantShopAccess.requireOwned(userId, shopId, "店铺不存在");

        long total = productMapper.selectCount(new LambdaQueryWrapper<Product>()
                .eq(Product::getShopId, shopId));
        long offset = pageSpec.offset();

        List<MerchantProductVO> items = productMapper.selectList(
                        new LambdaQueryWrapper<Product>()
                                .eq(Product::getShopId, shopId)
                                .orderByDesc(Product::getId)
                                .last("LIMIT " + offset + ", " + size))
                .stream()
                .map(product -> new MerchantProductVO(
                        product.getId(), product.getShopId(),
                        product.getCategoryId(), productMapper.selectBrandId(product.getId()),
                        product.getName(), product.getStatus().name()))
                .toList();

        return new MerchantProductPageVO(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantProductDetailVO merchantDetail(Long userId, Long productId) {
        SessionUser.requireId(userId);
        requireProductId(productId);

        Product product = requireOwnedProduct(userId, productId, false).product();

        List<ProductImage> images = productImageMapper.selectList(
                new LambdaQueryWrapper<ProductImage>()
                        .eq(ProductImage::getProductId, productId)
                        .orderByAsc(ProductImage::getSortOrder, ProductImage::getId));
        List<Long> imageMediaIds = images.stream().map(ProductImage::getMediaId).toList();
        Long coverMediaId = images.stream().filter(image -> Integer.valueOf(1).equals(image.getIsCover()))
                .map(ProductImage::getMediaId).findFirst().orElse(null);

        List<ProductOptionVO> options = loadOptions(productId);

        List<Sku> skuRows = skuMapper.selectList(
                        new LambdaQueryWrapper<Sku>()
                                .eq(Sku::getProductId, productId)
                                .orderByAsc(Sku::getId));
        Map<Long, List<Long>> skuValues = loadSkuValueIds(skuRows);
        List<MerchantSkuVO> skus = skuRows.stream()
                .map(sku -> new MerchantSkuVO(
                        sku.getId(),
                        productId,
                        skuValues.getOrDefault(sku.getId(), List.of()),
                        sku.getPrice(),
                        sku.getStock(),
                        false))
                .toList();

        return new MerchantProductDetailVO(
                product.getId(), product.getShopId(), product.getCategoryId(),
                productMapper.selectBrandId(productId), product.getName(), product.getDescription(),
                coverMediaId, product.getStatus().name(), imageMediaIds, options, skus);
    }

    private List<ProductOptionVO> loadOptions(Long productId) {
        List<ProductOption> options = optionMapper.selectList(
                new LambdaQueryWrapper<ProductOption>()
                        .eq(ProductOption::getProductId, productId)
                        .orderByAsc(ProductOption::getSortOrder, ProductOption::getId));
        if (options.isEmpty()) return List.of();
        Map<Long, List<ProductOptionValueVO>> values = new HashMap<>();
        for (ProductOptionValue value : valueMapper.selectList(
                new LambdaQueryWrapper<ProductOptionValue>()
                        .in(ProductOptionValue::getOptionId, options.stream().map(ProductOption::getId).toList())
                        .orderByAsc(ProductOptionValue::getSortOrder, ProductOptionValue::getId))) {
            values.computeIfAbsent(value.getOptionId(), ignored -> new ArrayList<>())
                    .add(new ProductOptionValueVO(value.getId(), value.getValueName()));
        }
        return options.stream().map(option -> new ProductOptionVO(
                option.getId(), option.getName(), values.getOrDefault(option.getId(), List.of()))).toList();
    }

    private Map<Long, List<Long>> loadSkuValueIds(List<Sku> skus) {
        Map<Long, List<Long>> values = new HashMap<>();
        List<Long> ids = skus.stream().map(Sku::getId).toList();
        // 控制 IN 列表大小，详情包含大量 SKU 时仍按少量批次读取。
        for (int start = 0; start < ids.size(); start += 200) {
            for (SkuOptionValueRow row : skuOptionValueMapper.selectSkuValues(
                    ids.subList(start, Math.min(start + 200, ids.size())))) {
                values.computeIfAbsent(row.getSkuId(), ignored -> new ArrayList<>()).add(row.getValueId());
            }
        }
        return values;
    }

    @Override
    @Transactional
    public ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto) {
        requireProductId(productId);
        if (!ProductStatus.OFF_SALE.name().equals(dto.getStatus())) {
            throw new BusinessException(400, "管理员只能下架商品");
        }
        if (productMapper.selectById(productId) == null) {
            throw new BusinessException(404, "商品不存在");
        }

        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .set(Product::getStatus, ProductStatus.OFF_SALE));
        queueSearchRefresh(productId);
        return new ProductStatusVO(productId, ProductStatus.OFF_SALE.name());
    }
}
