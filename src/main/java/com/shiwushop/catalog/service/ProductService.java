package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.product.MerchantProductCreateDTO;
import com.shiwushop.catalog.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.catalog.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.catalog.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.catalog.vo.product.MerchantProductDetailVO;
import com.shiwushop.catalog.vo.product.MerchantProductPageVO;
import com.shiwushop.catalog.vo.product.MerchantProductVO;
import com.shiwushop.catalog.vo.product.ProductOptionVO;
import com.shiwushop.catalog.vo.product.ProductStatusVO;
import com.shiwushop.catalog.vo.sku.MerchantSkuVO;


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

    /** 为商家商品创建一种新的 SKU 规格组合。 */
    MerchantSkuVO createMerchantSku(Long userId, Long productId, MerchantSkuCreateDTO dto);

    /** 校验上架条件并切换商家商品的展示状态。 */
    ProductStatusVO updateMerchantProductStatus(Long userId, Long productId, ProductStatusUpdateDTO dto);

    /** 管理员按审核规则强制下架指定商品。 */
    ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto);

    /** 返回商家管理页所需的商品、规格与 SKU 详情。 */
    MerchantProductDetailVO merchantDetail(Long userId, Long productId);

    /** 分页查询商家所属店铺的商品管理列表。 */
    MerchantProductPageVO listMerchantProducts(Long userId, Long shopId, int page, int size);

}
