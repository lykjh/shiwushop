package com.shiwushop.catalog.service;

import com.shiwushop.catalog.dto.product.PublicProductQuery;
import com.shiwushop.catalog.vo.product.PublicProductDetailVO;
import com.shiwushop.catalog.vo.product.PublicProductPageVO;

/** 公开商品读取与商家商品写入分离，首页缓存仅依赖此接口。 */
public interface PublicProductService {
    /** 按公开筛选条件和商品 ID 游标返回一批可见商品。 */
    PublicProductPageVO publicList(PublicProductQuery query);
    /** 读取一件可公开展示商品的完整详情。 */
    PublicProductDetailVO publicDetail(Long id);
}
