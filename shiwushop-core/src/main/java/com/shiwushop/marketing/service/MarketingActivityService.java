package com.shiwushop.marketing.service;

import com.shiwushop.marketing.dto.*;
import com.shiwushop.marketing.vo.ActivityAdminVO;
import com.shiwushop.marketing.vo.BudgetVO;

public interface MarketingActivityService {
    ActivityAdminVO.Page list(Long actor, Long shop, Long afterId, int size, String type, String status, String code);

    ActivityAdminVO.Version detail(Long actor, Long shop, Long id);

    ActivityAdminVO.Version create(Long actor, Long shop, String key, ActivityDraft draft);

    ActivityAdminVO.Version edit(Long actor, Long shop, Long id, String etag, ActivityDraft draft);

    ActivityAdminVO.Version submit(Long actor, Long shop, Long id, String etag);

    ActivityAdminVO.Version decide(Long actor, Long id, String etag, ActivityDecision decision);

    BudgetVO agree(Long actor, Long shop, Long id, String etag, ActivityAgreement agreement);

    ActivityAdminVO.Version revise(Long actor, Long shop, Long id, String key, ActivityReason reason);

    ActivityAdminVO.Version transition(Long actor, Long id, String etag, String command, ActivityReason reason);

    java.util.List<Long> due();

    void advance(Long id);
}
