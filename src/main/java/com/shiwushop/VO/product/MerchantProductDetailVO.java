package com.shiwushop.vo.product;

import com.shiwushop.vo.sku.MerchantSkuVO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MerchantProductDetailVO {
    private Long id;
    private Long shopId;
    private Long categoryId;
    private Long brandId;
    private String name;
    private String description;
    private Long coverMediaId;
    private String status;
    private List<Long> imageMediaIds;
    private List<ProductOptionVO> options;
    private List<MerchantSkuVO> skus;
}
