package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.BargainPublicVO;

public interface MarketingBargainQueryService {
    BargainPublicVO detail(long bargainId);

    BargainPublicVO.Mine mineDetail(long user, long bargainId);

    BargainPublicVO.Page mine(long user, Long afterId, int size, String status);
}
