package com.shiwushop.catalog.service.impl;

import com.shiwushop.catalog.entity.brand.Brand;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.product.ProductImage;
import com.shiwushop.catalog.entity.product.ProductOption;
import com.shiwushop.catalog.entity.product.ProductOptionValue;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.mapper.brand.BrandMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.catalog.mapper.product.ProductImageMapper;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionMapper;
import com.shiwushop.catalog.mapper.product.ProductOptionValueMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.catalog.mapper.product.SkuOptionValueMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;

import com.shiwushop.catalog.dto.product.MerchantProductCreateDTO;
import com.shiwushop.catalog.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductOptionSaveDTO;
import com.shiwushop.catalog.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.catalog.vo.product.MerchantProductDetailVO;
import com.shiwushop.catalog.vo.product.MerchantProductPageVO;
import com.shiwushop.catalog.vo.product.MerchantProductVO;
import com.shiwushop.catalog.vo.product.ProductOptionVO;
import com.shiwushop.catalog.vo.product.ProductOptionValueVO;
import com.shiwushop.catalog.vo.product.ProductStatusVO;
import com.shiwushop.catalog.vo.sku.MerchantSkuVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.common.media.MediaAssetAccess;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.catalog.entity.status.ProductStatus;
import com.shiwushop.catalog.service.CategoryService;
import com.shiwushop.catalog.service.ProductService;
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

/** 商家和管理员的商品变更入口；公开读取由 PublicProductServiceImpl 负责。 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;
    private final InventoryMovementMapper inventoryMovementMapper;
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
    private final ProductOptionReader optionReader;

    /** 将商品变化写入事务发件箱；事件写入失败时回滚商品变更，避免搜索索引漏更新。 */
    private void queueSearchRefresh(Long productId) {
        if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
            throw new IllegalStateException("商品搜索事件写入失败");
        }
    }

    /** 在查询前拒绝空值和非正数商品 ID，统一返回参数错误。 */
    private void requireProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(400, "商品ID必须大于0");
        }
    }

    /**
     * 确认商品所在店铺属于当前商家。写操作先锁店铺再锁商品，
     * 锁后复查店铺关联，防止归属变更与商家写入并发交错。
     */
    private OwnedProduct requireOwnedProduct(Long userId, Long productId, boolean lock) {
        Product product = productMapper.selectById(productId);
        if (product == null || product.getShopId() == null) {
            throw new BusinessException(404, "商品不存在");
        }
        Shop shop = lock
                ? merchantShopAccess.requireOwnedForUpdate(userId, product.getShopId(), "商品不存在")
                : merchantShopAccess.requireOwned(userId, product.getShopId(), "商品不存在");
        if (lock) {
            product = productMapper.selectForUpdate(productId);
            if (product == null || !shop.getId().equals(product.getShopId())) {
                throw new BusinessException(404, "商品不存在");
            }
        }
        return new OwnedProduct(product, shop);
    }

    /** 同时保存通过归属校验的商品和店铺，供上架等操作复用。 */
    private record OwnedProduct(Product product, Shop shop) { }

    /**
     * 在商家所属店铺创建草稿。校验分类、品牌和图片归属后保存商品、品牌关联与图片顺序，
     * 最后写搜索刷新事件；草稿尚不对公开列表展示。
     */
    @Override
    @Transactional
    public MerchantProductVO createDraft(Long userId, MerchantProductCreateDTO dto) {
        SessionUser.requireId(userId);

        Shop shop = merchantShopAccess.requireOwnedForUpdate(userId, dto.getShopId(), "店铺不存在");

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
        BusinessEventLog.committed("catalog", "product_draft_created", "product", product.getId());
        return new MerchantProductVO(
                product.getId(),
                product.getShopId(),
                product.getCategoryId(),
                dto.getBrandId(),
                product.getName(),
                product.getStatus().name()
        );
    }

    /**
     * 锁住商家商品后修改资料。图片列表未提交时保留原图；提交新列表时检查封面包含关系，
     * 在售商品不得移除所有图片或封面，更新后通知搜索索引。
     */
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
            BusinessEventLog.committed("catalog", "product_updated", "product", productId);

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

    /** 要求商品使用公开的二级或三级分类；可选品牌必须存在且启用。 */
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

    /** 拒绝重复图片，并要求指定的封面 ID 同时出现在商品图片列表中。 */
    private void validateImages(List<Long> imageIds, Long coverId) {
        if (new HashSet<>(imageIds).size() != imageIds.size()) {
            throw new BusinessException(400, "商品图片不能重复");
        }
        if (coverId != null && !imageIds.contains(coverId)) {
            throw new BusinessException(400, "封面必须包含在商品图片中");
        }
    }

    /** 逐张确认图片已经准备好且属于当前商家，避免引用其他用户的私有图片。 */
    private void validateOwnedMedia(List<Long> imageIds, Long userId) {
        for (Long mediaId : imageIds) {
            mediaAssetAccess.requireReadyOwned(mediaId, userId, "商品图片不存在或不可用");
        }
    }

    /**
     * 解析更新后的封面：显式新封面优先；替换图片列表但未给封面时清空封面；
     * 两者均未提交时沿用原封面。
     */
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

    /** 按请求顺序保存商品图片关联，并为唯一封面设置标记。 */
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

    /** 提交图片列表时整体替换关联；只提交封面时仅切换原列表中的封面标记。 */
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

    /**
     * 在商品未上架且没有 SKU 时整体替换规格选项，避免改变既有 SKU 签名和历史订单含义。
     * 删除旧规格、插入新规格值都在同一事务内完成。
     */
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
            BusinessEventLog.committed("catalog", "product_options_replaced", "product", productId);
            return insertProductOptions(productId, dto.getOptions());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "规格重复或已被SKU引用",e);
        }
    }

    /** 校验规格项名称不重复，且同一规格项内部的值不重复。 */
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

    /** 先删除商品旧规格值，再删除规格项，以满足外键引用顺序。 */
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

    /** 按输入顺序写入规格项和值，并返回数据库生成的 ID 供商家创建 SKU。 */
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

    /**
     * 锁住店铺、商品和 SKU 后修改价格与库存；库存变化同步写入调整流水。
     * SQL 更新前确认 SKU 属于目标商品，成功后投递搜索刷新事件。
     */
    @Override
    @Transactional
    public MerchantSkuVO updateMerchantSku(Long userId, Long productId, Long skuId, MerchantSkuUpdateDTO dto) {
        SessionUser.requireId(userId);
        if (productId == null || productId <= 0 || skuId == null || skuId <= 0) {
            throw new BusinessException(400, "ID必须大于0");
        }

        requireOwnedProduct(userId, productId, true);

        Sku sku = skuMapper.selectForUpdate(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new BusinessException(404, "SKU不存在");
        }

        skuMapper.update(null, new LambdaUpdateWrapper<Sku>()
                .eq(Sku::getId, skuId)
                .set(Sku::getPrice, dto.getPrice())
                .set(Sku::getStock, dto.getStock()));
        int stockDelta = dto.getStock() - sku.getStock();
        if (stockDelta != 0 && inventoryMovementMapper.insertMerchantAdjust(skuId, stockDelta,
                dto.getStock(), "MERCHANT_ADJUST:" + UUID.randomUUID()) != 1) {
            throw new IllegalStateException("商家库存调整流水写入失败");
        }
        queueSearchRefresh(productId);
        BusinessEventLog.committed("catalog", "sku_updated", "sku", skuId);

        return new MerchantSkuVO(
                skuId,
                productId,
                skuOptionValueMapper.selectValueIds(skuId),
                dto.getPrice(),
                dto.getStock(),
                false);
    }

    /**
     * 验证每个规格项恰好选择一个值；按规格顺序计算组合摘要，依靠唯一约束拒绝重复 SKU。
     * SKU、规格关联和搜索刷新事件在一个事务中提交。
     */
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
        BusinessEventLog.committed("catalog", "sku_created", "sku", sku.getId());
        return new MerchantSkuVO(
                sku.getId(), productId, orderedIds, dto.getPrice(), dto.getStock(), false);
    }

    /**
     * 确认提交的规格值全部属于该商品，且每个规格项恰好选中一个值。
     * 按规格项顺序返回 ID，使同一组合无论请求顺序如何都得到相同签名。
     */
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

    /** 店主切换商品上下架状态；上架前检查店铺、分类、品牌、图片和有效 SKU。 */
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
        BusinessEventLog.committed("catalog", "product_status_changed", "product", productId);

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

    /** 校验店铺归属和分页参数，按商品 ID 倒序列出商家管理页并返回总数。 */
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

    /** 读取商家商品详情，汇集图片、封面、规格选项与 SKU 及其规格值。 */
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

        List<ProductOptionVO> options = optionReader.loadOptions(productId);

        List<Sku> skuRows = skuMapper.selectList(
                        new LambdaQueryWrapper<Sku>()
                                .eq(Sku::getProductId, productId)
                                .orderByAsc(Sku::getId));
        Map<Long, List<Long>> skuValues = optionReader.loadSkuValueIds(skuRows);
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

    /** 管理员只能把商品设为下架；更新状态后写入搜索刷新事件。 */
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
        BusinessEventLog.committed("catalog", "product_admin_taken_down", "product", productId);
        return new ProductStatusVO(productId, ProductStatus.OFF_SALE.name());
    }
}
