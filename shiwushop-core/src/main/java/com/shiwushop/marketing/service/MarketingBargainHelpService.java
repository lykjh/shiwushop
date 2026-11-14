package com.shiwushop.marketing.service;

import com.shiwushop.marketing.vo.BargainHelpVO;

public interface MarketingBargainHelpService {
    BargainHelpVO help(Long helperUserId, long bargainId, String requestKey);
}
