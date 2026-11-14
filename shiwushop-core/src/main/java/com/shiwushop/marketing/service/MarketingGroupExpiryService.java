package com.shiwushop.marketing.service;

import java.util.List;

public interface MarketingGroupExpiryService {
    List<Long> due();

    void expire(long groupId);
}
