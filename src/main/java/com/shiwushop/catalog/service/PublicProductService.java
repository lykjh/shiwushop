package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.vo.product.PublicProductDetailVO;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;

/** 公开商品读取与商家商品写入分离，首页缓存仅依赖此接口。 */
public interface PublicProductService {
    PublicProductPageVO publicList(PublicProductQuery query);
    PublicProductDetailVO publicDetail(Long id);
}
