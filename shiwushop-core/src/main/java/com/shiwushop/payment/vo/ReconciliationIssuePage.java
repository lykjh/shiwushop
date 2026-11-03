package com.shiwushop.payment.vo;
import com.shiwushop.payment.entity.ReconciliationIssue;
import java.util.List;
public record ReconciliationIssuePage(List<ReconciliationIssue> items, boolean hasMore, Long nextCursor) {}
