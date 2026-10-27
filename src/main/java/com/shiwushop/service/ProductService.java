package com.shiwushop.service;

import com.shiwushop.dto.product.MerchantProductCreateDTO;
import com.shiwushop.dto.product.MerchantProductUpdateDTO;
import com.shiwushop.dto.product.ProductOptionsUpdateDTO;
import com.shiwushop.dto.product.ProductStatusUpdateDTO;
import com.shiwushop.dto.product.PublicProductQuery;
import com.shiwushop.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.vo.product.MerchantProductDetailVO;
import com.shiwushop.vo.product.MerchantProductPageVO;
import com.shiwushop.vo.product.MerchantProductVO;
import com.shiwushop.vo.product.ProductOptionVO;
import com.shiwushop.vo.product.ProductStatusVO;
import com.shiwushop.vo.product.PublicProductDetailVO;
import com.shiwushop.vo.product.PublicProductPageVO;
import com.shiwushop.vo.sku.MerchantSkuVO;


import java.util.List;

public interface ProductService {
    PublicProductPageVO publicList(PublicProductQuery query);

    PublicProductDetailVO publicDetail(Long id);

    MerchantProductVO createDraft(Long userId, MerchantProductCreateDTO dto);

    MerchantProductVO updateMerchantProduct(
            Long userId, Long productId, MerchantProductUpdateDTO dto);

    List<ProductOptionVO> replaceOptions(Long userId, Long productId, ProductOptionsUpdateDTO dto);

    MerchantSkuVO updateMerchantSku(Long userId, Long productId, Long skuId, MerchantSkuUpdateDTO dto);

    MerchantSkuVO createMerchantSku(Long userId, Long productId, MerchantSkuCreateDTO dto);

    ProductStatusVO updateMerchantProductStatus(Long userId, Long productId, ProductStatusUpdateDTO dto);

    ProductStatusVO adminTakeDown(Long productId, ProductStatusUpdateDTO dto);

    MerchantProductDetailVO merchantDetail(Long userId, Long productId);

    MerchantProductPageVO listMerchantProducts(Long userId, Long shopId, int page, int size);

}
