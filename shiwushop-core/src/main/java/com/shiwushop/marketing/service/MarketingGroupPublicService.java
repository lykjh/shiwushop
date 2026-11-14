package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.GroupPublicVO;

public interface MarketingGroupPublicService {
    GroupPublicVO.Page available(long activityId, long activitySkuId, Long afterId, int size);

    GroupPublicVO detail(long groupId);
}
