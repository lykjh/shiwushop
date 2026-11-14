package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.PointProductPage;

public interface MarketingPointProductService {
    PointProductPage available(Long shopId, Long afterId, int size);
}
