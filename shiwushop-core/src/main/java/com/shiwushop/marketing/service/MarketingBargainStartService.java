package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.BargainPublicVO;

public interface MarketingBargainStartService {
    BargainPublicVO.Mine start(Long user, long activitySkuId, String requestKey);
}
