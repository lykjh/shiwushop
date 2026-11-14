package com.shiwushop.marketing.service;

import java.util.List;

public interface MarketingBargainExpiryService {
    List<Long> due();

    void expire(long bargainId);
}
