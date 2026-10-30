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
