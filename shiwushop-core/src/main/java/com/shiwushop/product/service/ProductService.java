package com.shiwushop.product.service;

import com.shiwushop.product.dto.product.MerchantProductCreateDTO;
import com.shiwushop.product.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.product.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.product.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.product.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.product.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.product.vo.product.MerchantProductDetailVO;
import com.shiwushop.product.vo.product.MerchantProductPageVO;
import com.shiwushop.product.vo.product.MerchantProductVO;
import com.shiwushop.product.vo.product.ProductOptionVO;
import com.shiwushop.product.vo.product.ProductStatusVO;
import com.shiwushop.product.vo.product.ProductListingVO;
import com.shiwushop.product.vo.product.PendingProductListingPage;
import com.shiwushop.product.dto.product.ProductListingDecisionDTO;
import com.shiwushop.product.dto.product.ProductDisplayAttributesDTO;
import com.shiwushop.product.vo.product.DisplayAttributeVO;
import com.shiwushop.product.vo.sku.MerchantSkuVO;
import com.shiwushop.product.vo.sku.SkuStatusVO;

import java.util.List;

public interface ProductService {
    /** 商家在所属店铺创建商品草稿。 */
    MerchantProductVO createDraft(Long userId, MerchantProductCreateDTO dto);

    /** 修改商家自己的商品资料与图片关联。 */
    MerchantProductVO updateMerchantProduct(
            Long userId, Long productId, MerchantProductUpdateDTO dto);

    /** 整体替换商品规格选项，并检查已有 SKU 对规格值的引用。 */
    List<ProductOptionVO> replaceOptions(Long userId, Long productId, ProductOptionsUpdateDTO dto);

    /** 修改商品下已存在 SKU 的价格、库存等商家字段。 */
    MerchantSkuVO updateMerchantSku(Long userId, Long productId, Long skuId, MerchantSkuUpdateDTO dto);

    /** 库存岗位只改普通库存，不接触价格或销售规格。 */
    MerchantSkuVO updateSkuStock(Long userId, Long productId, Long skuId, int stock);

    /** 为商家商品创建一种新的 SKU 规格组合。 */
    MerchantSkuVO createMerchantSku(Long userId, Long productId, MerchantSkuCreateDTO dto);

    SkuStatusVO updateSkuStatus(Long userId, Long productId, Long skuId, String status);

    /** 校验上架条件并切换商家商品的展示状态。 */
    ProductStatusVO updateMerchantProductStatus(Long userId, Long productId, ProductStatusUpdateDTO dto);

    ProductStatusVO archive(Long userId, Long productId, Long revision);

    ProductStatusVO restore(Long userId, Long productId);

    ProductListingVO submitListing(Long userId, Long productId, Long revision);
    ProductListingVO decideListing(Long reviewerId, Long productId, ProductListingDecisionDTO decision);

    PendingProductListingPage listPendingListings(int page, int size);

    PendingProductListingPage.Detail pendingListingDetail(Long productId);

    void requirePendingListingImage(Long productId, Long revision, Long mediaId);

    /** 展示参数整组替换，仅改变待审资料版本，不触及销售规格、价格或库存。 */
    List<DisplayAttributeVO> replaceDisplayAttributes(Long userId, Long productId, ProductDisplayAttributesDTO request);

    /** 返回商家管理页所需的商品、规格与 SKU 详情。 */
    MerchantProductDetailVO merchantDetail(Long userId, Long productId);

    /** 分页查询商家所属店铺的商品管理列表。 */
    MerchantProductPageVO listMerchantProducts(Long userId, Long shopId, int page, int size);
}
