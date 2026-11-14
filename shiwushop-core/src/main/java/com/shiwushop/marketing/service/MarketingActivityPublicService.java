package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.ActivityPublicVO;

public interface MarketingActivityPublicService {
    ActivityPublicVO.Page list(Long afterId, int size, String type, Long shop, Long sku);

    ActivityPublicVO detail(Long id);
}
