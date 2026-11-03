package com.shiwushop.aftersale.service.impl;

import com.shiwushop.aftersale.dto.timeout.CloseAfterSaleTimeoutDTO;
import com.shiwushop.aftersale.entity.AfterSaleCase;
import com.shiwushop.aftersale.entity.status.AfterSaleStatus;
import com.shiwushop.aftersale.entity.status.AfterSaleType;
import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;
import com.shiwushop.aftersale.mapper.AfterSaleCaseMapper;
import com.shiwushop.aftersale.mapper.timeout.AfterSaleTimeoutMapper;
import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
import com.shiwushop.aftersale.vo.timeout.AfterSaleTimeoutPageVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.service.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AfterSaleTimeoutServiceImpl implements AfterSaleTimeoutService {
    private final AfterSaleTimeoutMapper tasks;
    private final AccountAccess accounts;
    private final AfterSaleCaseMapper cases;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    @Value("${shop.aftersale.timeout-review-hours:48}") private long reviewHours;
    @Value("${shop.aftersale.timeout-return-days:7}") private long returnDays;
    @Value("${shop.aftersale.timeout-receipt-days:7}") private long receiptDays;
    @Value("${shop.aftersale.timeout-refund-hours:24}") private long refundHours;
    @Value("${shop.aftersale.timeout-rule-version:1}") private int ruleVersion;

    /** 业务状态变更同事务登记下一期限并过期旧阶段，保留已关闭历史。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStageChanged(Long caseId) {
        AfterSaleCase sale = cases.selectForUpdate(caseId);
        String stage = switch (sale.getStatus()) {
            case REQUESTED -> "REVIEW";
            case WAITING_RETURN -> "RETURN";
            case WAITING_RECEIPT -> "RECEIPT";
            case REFUNDING -> "REFUND";
            default -> null;
        };
        LocalDateTime basis = stage == null ? null : basis(sale, stage);
        if (basis == null) {
            tasks.staleAll(caseId);
            return;
        }
        tasks.staleOther(caseId, stage, basis);
        LocalDateTime due = switch (stage) {
            case "REVIEW" -> basis.plusHours(reviewHours);
            case "RETURN" -> basis.plusDays(returnDays);
            case "RECEIPT" -> basis.plusDays(receiptDays);
            case "REFUND" -> basis.plusHours(refundHours);
            default -> throw new IllegalStateException("未知售后超时阶段");
        };
        tasks.schedule(caseId, stage, basis, due, ruleVersion, LocalDateTime.now(clock));
    }

    /** 只扫描到期队列，逐条在售后和关联行锁下复核，再激活或过期。 */
    @Override
    public void scan() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Long id : tasks.dueIds(now)) {
            AfterSaleTimeoutTask found = tasks.selectById(id);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AfterSaleCase sale = cases.selectForUpdate(found.getCaseId());
                LocalDateTime current = basis(sale, found.getStage());
                AfterSaleTimeoutTask task = tasks.selectForUpdate(id);
                if (!task.getBasisAt().equals(current)) {
                    tasks.staleScheduled(id);
                } else if (tasks.activate(id, now) == 1) {
                    BusinessEventLog.committed("aftersale", "timeout_opened", "timeout_task", id);
                }
            });
        }
    }

    /** 以当前读复核阶段依据，退款一旦有任意尝试就不再属于未发起。 */
    private LocalDateTime basis(AfterSaleCase sale, String stage) {
        return switch (stage) {
            case "REVIEW" -> sale.getStatus() == AfterSaleStatus.REQUESTED ? sale.getRequestedAt() : null;
            case "RETURN" -> sale.getStatus() == AfterSaleStatus.WAITING_RETURN ? sale.getReviewedAt() : null;
            case "RECEIPT" -> {
                if (sale.getStatus() != AfterSaleStatus.WAITING_RECEIPT) yield null;
                var shipment = tasks.shipmentForUpdate(sale.getId());
                yield shipment == null ? null : shipment.getSentAt();
            }
            case "REFUND" -> {
                if (sale.getStatus() != AfterSaleStatus.REFUNDING || tasks.latestRefundForUpdate(sale.getId()) != null) yield null;
                if (sale.getType() == AfterSaleType.REFUND_ONLY) yield sale.getReviewedAt();
                var shipment = tasks.shipmentForUpdate(sale.getId());
                yield shipment == null ? null : shipment.getReceivedAt();
            }
            default -> throw new IllegalStateException("未知售后超时阶段");
        };
    }

    /** 管理员读取已激活待办及处理记录。 */
    @Override
    public AfterSaleTimeoutTask detail(Long adminId, Long id) {
        accounts.requireAdmin(adminId);
        AfterSaleTimeoutTask task = requireTask(id);
        if (task.getOpenedAt() == null) throw new BusinessException(404, "待办不存在");
        return task;
    }

    /** 先锁售后及关联行再锁待办，只有一名管理员可以领取。 */
    @Override
    public AfterSaleTimeoutTask claim(Long adminId, Long id) {
        accounts.requireAdmin(adminId);
        AfterSaleTimeoutTask found = requireTask(id);
        return new TransactionTemplate(transactions).execute(status -> {
            AfterSaleCase sale = cases.selectForUpdate(found.getCaseId());
            LocalDateTime current = basis(sale, found.getStage());
            AfterSaleTimeoutTask task = tasks.selectForUpdate(id);
            if ("CLAIMED".equals(task.getStatus()) && adminId.equals(task.getAssigneeId())
                    && task.getBasisAt().equals(current)) return task;
            if (!"OPEN".equals(task.getStatus()) || !task.getBasisAt().equals(current))
                throw new BusinessException(409, "待办不可领取");
            if (tasks.claim(id, adminId, LocalDateTime.now(clock)) != 1) throw new BusinessException(409, "待办已被领取");
            BusinessEventLog.committed("aftersale", "timeout_claimed", "timeout_task", id);
            return tasks.selectById(id);
        });
    }

    /** 经办人提交证据和结论后关闭；相同重复请求返回原记录。 */
    @Override
    public AfterSaleTimeoutTask close(Long adminId, Long id, CloseAfterSaleTimeoutDTO dto) {
        accounts.requireAdmin(adminId);
        AfterSaleTimeoutTask found = requireTask(id);
        String evidence = dto.evidence().strip();
        String conclusion = dto.conclusion().strip();
        return new TransactionTemplate(transactions).execute(status -> {
            AfterSaleCase sale = cases.selectForUpdate(found.getCaseId());
            LocalDateTime current = basis(sale, found.getStage());
            AfterSaleTimeoutTask task = tasks.selectForUpdate(id);
            if ("CLOSED".equals(task.getStatus()) && adminId.equals(task.getAssigneeId())
                    && evidence.equals(task.getEvidence()) && conclusion.equals(task.getConclusion())) return task;
            if (!"CLAIMED".equals(task.getStatus()) || !adminId.equals(task.getAssigneeId())
                    || !task.getBasisAt().equals(current)) throw new BusinessException(409, "待办不可关闭");
            if (tasks.close(id, adminId, evidence, conclusion, LocalDateTime.now(clock)) != 1)
                throw new BusinessException(409, "待办状态冲突");
            BusinessEventLog.committed("aftersale", "timeout_closed", "timeout_task", id);
            return tasks.selectById(id);
        });
    }

    /** 以 404 隐藏不存在的待办。 */
    private AfterSaleTimeoutTask requireTask(Long id) {
        AfterSaleTimeoutTask task = tasks.selectById(id);
        if (task == null) throw new BusinessException(404, "待办不存在");
        return task;
    }

    /** 管理员按 ID 倒序读取已经激活的售后待办。 */
    @Override
    public AfterSaleTimeoutPageVO list(Long adminId, Long beforeId, int size) {
        accounts.requireAdmin(adminId);
        var found = tasks.page(beforeId == null ? Long.MAX_VALUE : beforeId, size + 1);
        boolean more = found.size() > size;
        var page = more ? found.subList(0, size) : found;
        return new AfterSaleTimeoutPageVO(page, more, more ? page.getLast().getId() : null);
    }
}
