package com.shiwushop.marketing.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.MarketingSettings;
import com.shiwushop.marketing.mapper.MarketingGroupMapper;
import com.shiwushop.marketing.service.MarketingGroupPublicService;
import com.shiwushop.marketing.vo.GroupPublicVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class MarketingGroupPublicServiceImpl implements MarketingGroupPublicService {
    private final MarketingGroupMapper mapper;
    private final MarketingSettings settings;
    private final Clock businessClock;

    @Override
    @Transactional(readOnly = true)
    public GroupPublicVO.Page available(long activityId, long activitySkuId, Long afterId, int size) {
        enabled();
        var rows = mapper.available(activityId, activitySkuId, afterId, size + 1,
                LocalDateTime.now(businessClock));
        boolean more = rows.size() > size;
        var items = rows.stream().limit(size).map(MarketingGroupPublicServiceImpl::view).toList();
        return new GroupPublicVO.Page(items, more, more ? items.getLast().id() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public GroupPublicVO detail(long groupId) {
        enabled();
        var row = mapper.publicGroup(groupId);
        if (row == null)
            throw new BusinessException(404, "拼团不存在");
        return view(row);
    }

    private void enabled() {
        if (!settings.enabled())
            throw new BusinessException(404, "营销功能尚未启用");
    }

    private static GroupPublicVO view(MarketingGroupMapper.GroupRow row) {
        return new GroupPublicVO(row.id(), row.activityId(), row.activitySkuId(), row.skuId(), row.status(),
                row.requiredMembers(), row.paidMembers(),
                row.requiredMembers() - row.reservedMembers() - row.paidMembers(),
                row.openedAt().atOffset(ZoneOffset.ofHours(8)), row.expiresAt().atOffset(ZoneOffset.ofHours(8)),
                row.succeededAt() == null ? null : row.succeededAt().atOffset(ZoneOffset.ofHours(8)));
    }
}
