package com.shiwushop.commerce.service.impl;

import com.shiwushop.commerce.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.commerce.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleVO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleSliceVO;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.auth.MerchantShopAccess;
import com.shiwushop.common.pagination.PageSpec;
import com.shiwushop.commerce.entity.after_sale.AfterSaleCase;
import com.shiwushop.commerce.entity.after_sale.AfterSaleItem;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.order.OrderItem;
import com.shiwushop.commerce.entity.payment.PaymentAttempt;
import com.shiwushop.commerce.entity.payment.RefundAttempt;
import com.shiwushop.catalog.entity.review.ProductReview;
import com.shiwushop.commerce.entity.shipment.ReturnShipment;
import com.shiwushop.commerce.entity.shipment.Shipment;
import com.shiwushop.commerce.entity.status.AfterSaleStatus;
import com.shiwushop.commerce.entity.status.AfterSaleType;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.entity.status.PaymentAttemptStatus;
import com.shiwushop.commerce.entity.status.RefundAttemptStatus;
import com.shiwushop.commerce.entity.status.ShipmentStatus;
import com.shiwushop.commerce.mapper.after_sale.AfterSaleAuditMapper;
import com.shiwushop.commerce.mapper.after_sale.AfterSaleCaseMapper;
import com.shiwushop.commerce.mapper.after_sale.AfterSaleItemMapper;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.commerce.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.commerce.mapper.payment.RefundAttemptMapper;
import com.shiwushop.catalog.mapper.review.ProductReviewMapper;
import com.shiwushop.commerce.mapper.shipment.ReturnShipmentMapper;
import com.shiwushop.commerce.mapper.shipment.ShipmentMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.commerce.payment.RefundGateway;
import com.shiwushop.commerce.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleServiceImpl implements AfterSaleService {
    private static final int APPLY_DAYS_AFTER_CONFIRM = 7;
    private static final Duration RECONCILE_CLAIM_LEASE = Duration.ofMinutes(2);
    private final AfterSaleCaseMapper cases;
    private final AfterSaleItemMapper items;
    private final ReturnShipmentMapper returns;
    private final RefundAttemptMapper refunds;
    private final AfterSaleAuditMapper audit;
    private final OrderMapper orders;
    private final OrderItemMapper orderItems;
    private final ShipmentMapper shipments;
    private final PaymentAttemptMapper payments;
    private final SkuMapper skus;
    private final InventoryMovementMapper movements;
    private final OutboxEventMapper outboxEventMapper;
    private final ProductReviewMapper reviews;
    private final MerchantShopAccess merchantAccess;
    private final AccountAccess accountAccess;
    private final RefundGateway gateway;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    @Value("${shop.payment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    /** 校验售后明细与请求键，在订单锁下计算可退件数和金额后创建申请。 */
    @Override
    public AfterSaleVO create(Long buyerId, Long orderId, String requestKey, AfterSaleCreateDTO request) {
        accountAccess.checkActive(buyerId);
        requireId(orderId);
        String key = text(requestKey, 64);
        if (request == null || request.items() == null || request.items().isEmpty()
                || request.items().size() > 100) throw new BusinessException(400, "售后商品不能为空");
        AfterSaleType type = type(request.type());
        String reason = text(request.reason(), 500);
        Long caseId = tx().execute(status -> createLocked(buyerId, orderId, key, type, reason, request.items()));
        return view(caseId);
    }

    /** 锁订单后计算已占用的件数和金额，避免并发申请超过可退余额。 */
    private Long createLocked(Long buyerId, Long orderId, String requestKey, AfterSaleType type, String reason,
                              List<AfterSaleCreateDTO.Item> requestedItems) {
        Order order = orders.selectForUpdate(orderId);
        if (order == null || !buyerId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        AfterSaleCase previous = cases.selectByRequestKey(orderId, requestKey);
        if (previous != null) {
            if (previous.getType() != type || !previous.getReason().equals(reason)
                    || !sameItems(previous.getId(), requestedItems))
                throw new BusinessException(409, "幂等键对应的申请内容不同");
            return previous.getId();
        }
        if (order.getStatus() != OrderStatus.PAID) throw new BusinessException(409, "订单尚未付款");
        Shipment shipment = shipments.selectByOrderId(orderId);
        if (type == AfterSaleType.RETURN_REFUND && shipment == null)
            throw new BusinessException(409, "订单尚未发货，不能退货退款");
        LocalDateTime now = LocalDateTime.now(clock);
        if (shipment != null && shipment.getStatus() == ShipmentStatus.CONFIRMED
                && now.isAfter(shipment.getConfirmedAt().plusDays(APPLY_DAYS_AFTER_CONFIRM))) {
            throw new BusinessException(409, "售后申请期限已过");
        }
        Set<Long> seen = new HashSet<>();
        List<AfterSaleItem> lines = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        for (AfterSaleCreateDTO.Item requested : requestedItems) {
            if (requested == null || requested.orderItemId() == null || requested.quantity() == null
                    || requested.quantity() <= 0 || !seen.add(requested.orderItemId())) {
                throw new BusinessException(400, "售后商品明细无效或重复");
            }
            OrderItem original = orderItems.selectById(requested.orderItemId());
            if (original == null || !orderId.equals(original.getOrderId()))
                throw new BusinessException(404, "订单商品不存在");
            int remaining = original.getQuantity() - items.reservedQuantity(original.getId());
            if (requested.quantity() > remaining) throw new BusinessException(409, "申请件数超过可退件数");
            AfterSaleItem line = new AfterSaleItem();
            line.setOrderItemId(original.getId());
            line.setQuantity(requested.quantity());
            line.setRefundAmount(original.getUnitPrice().multiply(BigDecimal.valueOf(requested.quantity())));
            amount = amount.add(line.getRefundAmount());
            lines.add(line);
        }
        PaymentAttempt paid = paidAttempt(order);
        if (items.reservedAmount(orderId).add(amount).compareTo(paid.getAmount()) > 0)
            throw new BusinessException(409, "退款金额超过已支付金额");
        AfterSaleCase sale = new AfterSaleCase();
        sale.setOrderId(orderId);
        sale.setRequestKey(requestKey);
        sale.setType(type);
        sale.setStatus(AfterSaleStatus.REQUESTED);
        sale.setReason(reason);
        sale.setRefundAmount(amount);
        sale.setRequestedAt(now);
        cases.insert(sale);
        for (AfterSaleItem line : lines) {
            line.setCaseId(sale.getId());
            items.insert(line);
        }
        audit.record(buyerId, "AFTER_SALE_REQUESTED", sale.getId(), "买家申请售后", now);
        return sale.getId();
    }

    /** 幂等重试时比较原申请与本次提交的商品 ID 和数量。 */
    private boolean sameItems(Long caseId, List<AfterSaleCreateDTO.Item> requested) {
        List<AfterSaleItem> existing = items.selectForCase(caseId);
        if (existing.size() != requested.size()) return false;
        for (AfterSaleItem line : existing) {
            long matches = requested.stream().filter(item -> item != null
                    && line.getOrderItemId().equals(item.orderItemId())
                    && line.getQuantity().equals(item.quantity())).count();
            if (matches != 1) return false;
        }
        return true;
    }

    /** 只列出买家所属订单的售后申请，按 ID 游标分页。 */
    @Override
    public AfterSaleSliceVO buyerList(Long buyerId, Long orderId, Long beforeId, int size) {
        buyerOrder(buyerId, orderId);
        int limit = PageSpec.of(1, size).size();
        return slice(cases.selectBuyerIds(orderId, cursor(beforeId), limit + 1), limit);
    }

    /** 核对店铺归属后按状态和游标列出本店售后申请。 */
    @Override
    public AfterSaleSliceVO merchantList(Long merchantId, Long shopId, Long beforeId,
                                         String status, int size) {
        requireId(shopId);
        merchantAccess.requireOwned(merchantId, shopId, "店铺不存在");
        int limit = PageSpec.of(1, size).size();
        AfterSaleStatus filter = status == null || status.isBlank() ? null : status(status);
        return slice(cases.selectMerchantIds(shopId, cursor(beforeId), filter, limit + 1), limit);
    }

    /** 多取一条判断下一页，逐条组装售后详情及下一页游标。 */
    private AfterSaleSliceVO slice(List<Long> ids, int limit) {
        List<Long> page = ids.stream().limit(limit).toList();
        List<AfterSaleVO> rows = page.stream().map(this::view).toList();
        Long next = ids.size() > limit ? page.getLast() : null;
        return new AfterSaleSliceVO(rows, next);
    }

    /** 校验售后分页游标；首页使用最大 ID 作为起点。 */
    private static Long cursor(Long beforeId) {
        if (beforeId != null) requireId(beforeId);
        return beforeId == null ? Long.MAX_VALUE : beforeId;
    }

    /** 解析售后状态筛选值，非法枚举返回参数错误。 */
    private static AfterSaleStatus status(String value) {
        try {
            return AfterSaleStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "售后状态无效");
        }
    }

    /** 仅允许订单买家读取对应售后详情。 */
    @Override
    public AfterSaleVO buyerDetail(Long buyerId, Long caseId) {
        AfterSaleCase sale = requireCase(caseId);
        buyerOrder(buyerId, sale.getOrderId());
        return view(caseId);
    }

    /** 仅允许所属店主读取对应售后详情。 */
    @Override
    public AfterSaleVO merchantDetail(Long merchantId, Long shopId, Long caseId) {
        merchantOrder(merchantId, shopId, requireCase(caseId).getOrderId());
        return view(caseId);
    }

    /** 店主审核申请并推进售后状态；退货退款先等待寄回，退款申请进入退款中。 */
    @Override
    public AfterSaleVO review(Long merchantId, Long shopId, Long caseId, AfterSaleReviewDTO request) {
        if (request == null || request.approved() == null) throw new BusinessException(400, "审核结果不能为空");
        String reviewReason = request.approved() ? optionalText(request.reason(), 500)
                : text(request.reason(), 500);
        tx().executeWithoutResult(status -> {
            AfterSaleCase found = requireCase(caseId);
            merchantOrderForUpdate(merchantId, shopId, found.getOrderId());
            orders.selectForUpdate(found.getOrderId());
            AfterSaleCase sale = cases.selectForUpdate(caseId);
            if (sale.getStatus() != AfterSaleStatus.REQUESTED) throw new BusinessException(409, "售后申请已审核");
            AfterSaleStatus next = request.approved()
                    ? sale.getType() == AfterSaleType.RETURN_REFUND
                    ? AfterSaleStatus.WAITING_RETURN : AfterSaleStatus.REFUNDING
                    : AfterSaleStatus.REJECTED;
            LocalDateTime now = LocalDateTime.now(clock);
            if (cases.review(caseId, next, merchantId, reviewReason, now,
                    request.approved() ? null : now) != 1) throw new BusinessException(409, "审核状态冲突");
            audit.record(merchantId, request.approved() ? "AFTER_SALE_APPROVED" : "AFTER_SALE_REJECTED",
                    caseId, "商家审核售后", now);
        });
        return view(caseId);
    }

    /** 买家登记退货运单；相同运单重复提交返回原结果。 */
    @Override
    public AfterSaleVO sendReturn(Long buyerId, Long caseId, ReturnShipmentDTO request) {
        String carrier = text(request == null ? null : request.carrierName(), 80);
        String tracking = text(request == null ? null : request.trackingNo(), 100);
        tx().executeWithoutResult(status -> {
            AfterSaleCase found = requireCase(caseId);
            buyerOrder(buyerId, found.getOrderId());
            orders.selectForUpdate(found.getOrderId());
            AfterSaleCase sale = cases.selectForUpdate(caseId);
            if (sale.getType() != AfterSaleType.RETURN_REFUND) throw new BusinessException(409, "不是退货退款申请");
            ReturnShipment existing = returns.selectByCaseId(caseId);
            if (existing != null) {
                if (carrier.equals(existing.getCarrierName()) && tracking.equals(existing.getTrackingNo())) return;
                throw new BusinessException(409, "退货运单已录入，信息不同");
            }
            if (sale.getStatus() != AfterSaleStatus.WAITING_RETURN)
                throw new BusinessException(409, "当前不能填写退货运单");
            LocalDateTime now = LocalDateTime.now(clock);
            ReturnShipment shipment = new ReturnShipment();
            shipment.setCaseId(caseId);
            shipment.setCarrierName(carrier);
            shipment.setTrackingNo(tracking);
            shipment.setSentAt(now);
            returns.insert(shipment);
            if (cases.transition(caseId, AfterSaleStatus.WAITING_RETURN, AfterSaleStatus.WAITING_RECEIPT) != 1)
                throw new BusinessException(409, "退货状态冲突");
            audit.record(buyerId, "RETURN_SHIPPED", caseId, "买家填写退货运单", now);
        });
        return view(caseId);
    }

    /** 店主确认退货签收；可重新销售时返还库存并记录流水。 */
    @Override
    public AfterSaleVO receiveReturn(Long merchantId, Long shopId, Long caseId, ReturnReceiptDTO request) {
        if (request == null || request.restockable() == null) throw new BusinessException(400, "请确认商品是否可重新销售");
        tx().executeWithoutResult(status -> {
            AfterSaleCase found = requireCase(caseId);
            merchantOrderForUpdate(merchantId, shopId, found.getOrderId());
            orders.selectForUpdate(found.getOrderId());
            AfterSaleCase sale = cases.selectForUpdate(caseId);
            if (sale.getStatus() != AfterSaleStatus.WAITING_RECEIPT)
                throw new BusinessException(409, "当前不能确认收货");
            ReturnShipment shipment = returns.selectByCaseId(caseId);
            if (shipment == null || shipment.getReceivedAt() != null)
                throw new BusinessException(409, "退货运单状态异常");
            LocalDateTime now = LocalDateTime.now(clock);
            if (returns.markReceived(caseId, merchantId, now) != 1
                    || cases.transition(caseId, AfterSaleStatus.WAITING_RECEIPT, AfterSaleStatus.REFUNDING) != 1)
                throw new BusinessException(409, "退货签收状态冲突");
            if (request.restockable()) restoreStock(caseId, "RETURN_RECEIPT");
            audit.record(merchantId, "RETURN_RECEIVED", caseId, "商家确认收到退货", now);
        });
        return view(caseId);
    }

    /** 先提交本地退款尝试，再在事务外调用渠道；结果未知时保留请求号供核对。 */
    @Override
    public AfterSaleVO refund(Long merchantId, Long shopId, Long caseId) {
        // 先提交本地退款尝试，再调用外部渠道；超时也保留同一请求号供查询和重试。
        RefundContext context = tx().execute(status -> prepareRefund(merchantId, shopId, caseId));
        if (context == null) return view(caseId);
        if (!context.newAttempt() && !LocalDateTime.now(clock).isBefore(context.attempt().getRequestedAt().plusSeconds(10))) {
            if (queryAndSettle(context)) return view(caseId);
        } else if (!context.newAttempt()) {
            return view(caseId);
        }
        try {
            RefundGateway.Result result = gateway.refund(context.payment().getOutTradeNo(),
                    context.payment().getAlipayTradeNo(), context.attempt().getOutRequestNo(),
                    context.attempt().getAmount());
            if (result.succeeded()) settleSuccess(context, result, false);
            else markUnknown(context.attempt().getId());
        } catch (RuntimeException unknown) {
            log.warn("退款调用结果未知，attemptId={}", context.attempt().getId(), unknown);
            markUnknown(context.attempt().getId());
        }
        return view(caseId);
    }

    /** 店主主动查询未确定的退款，并把渠道成功结果结算到售后状态。 */
    @Override
    public AfterSaleVO syncRefund(Long merchantId, Long shopId, Long caseId) {
        RefundContext context = tx().execute(status -> {
            AfterSaleCase sale = requireCase(caseId);
            Order order = merchantOrderForUpdate(merchantId, shopId, sale.getOrderId());
            if (sale.getStatus() == AfterSaleStatus.REFUNDED) return null;
            RefundAttempt attempt = refunds.selectLatest(caseId);
            if (attempt == null || sale.getStatus() != AfterSaleStatus.REFUNDING)
                throw new BusinessException(409, "尚未发起退款");
            if (attempt.getStatus() == RefundAttemptStatus.SUCCESS) return null;
            if (attempt.getStatus() == RefundAttemptStatus.FAILED)
                throw new BusinessException(409, "退款记录已标记失败，需要人工核查");
            return LocalDateTime.now(clock).isBefore(attempt.getRequestedAt().plusSeconds(10))
                    ? null : new RefundContext(attempt, paidAttempt(order), false);
        });
        // 渠道查询在事务外执行；已受理的退款核对继续由幂等状态保护。
        if (context != null) queryAndSettle(context);
        return view(caseId);
    }

    /** 锁定订单和售后申请，复用现有尝试或创建金额不超出原支付的退款请求。 */
    private RefundContext prepareRefund(Long merchantId, Long shopId, Long caseId) {
        AfterSaleCase found = requireCase(caseId);
        merchantOrderForUpdate(merchantId, shopId, found.getOrderId());
        Order order = orders.selectForUpdate(found.getOrderId());
        AfterSaleCase sale = cases.selectForUpdate(caseId);
        if (sale.getStatus() == AfterSaleStatus.REFUNDED) return null;
        if (sale.getStatus() != AfterSaleStatus.REFUNDING)
            throw new BusinessException(409, "售后尚未进入退款阶段");
        if (!gateway.configured()) throw new BusinessException(503, "退款渠道未配置");
        PaymentAttempt paid = paidAttempt(order);
        RefundAttempt attempt = refunds.selectLatest(caseId);
        if (attempt != null) {
            if (attempt.getStatus() == RefundAttemptStatus.SUCCESS)
                throw new BusinessException(409, "退款记录与售后状态不一致");
            if (attempt.getStatus() == RefundAttemptStatus.FAILED)
                throw new BusinessException(409, "退款记录已标记失败，需要人工核查");
            if (!attempt.getPaymentAttemptId().equals(paid.getId())
                    || attempt.getAmount().compareTo(sale.getRefundAmount()) != 0)
                throw new BusinessException(409, "退款记录金额不一致");
            return new RefundContext(attempt, paid, false);
        }
        BigDecimal successful = refunds.successfulAmount(order.getId());
        if (successful.add(sale.getRefundAmount()).compareTo(paid.getAmount()) > 0)
            throw new BusinessException(409, "退款累计金额超过支付金额");
        attempt = new RefundAttempt();
        attempt.setCaseId(caseId);
        attempt.setPaymentAttemptId(paid.getId());
        attempt.setOutRequestNo("SHIWUSHOPR" + UUID.randomUUID().toString().replace("-", ""));
        attempt.setAmount(sale.getRefundAmount());
        attempt.setStatus(RefundAttemptStatus.PENDING);
        attempt.setRequestedAt(LocalDateTime.now(clock));
        refunds.insert(attempt);
        audit.record(merchantId, "REFUND_REQUESTED", caseId, "商家发起退款", attempt.getRequestedAt());
        return new RefundContext(attempt, paid, true);
    }

    /** 查询退款渠道并尝试结算；供店主主动同步使用。 */
    private boolean queryAndSettle(RefundContext context) {
        return queryAndSettle(context, null);
    }

    /** 查询渠道结果；自动对账时用租约令牌阻止过期任务写入。 */
    private boolean queryAndSettle(RefundContext context, String reconcileToken) {
        try {
            RefundGateway.Result result = gateway.query(context.payment().getOutTradeNo(),
                    context.payment().getAlipayTradeNo(), context.attempt().getOutRequestNo());
            if (!result.succeeded()) {
                markUnknown(context.attempt().getId(), reconcileToken);
                return false;
            }
            settleSuccess(context, result, true, reconcileToken);
            return true;
        } catch (RuntimeException unknown) {
            log.warn("退款查询结果未知，attemptId={}", context.attempt().getId(), unknown);
            markUnknown(context.attempt().getId(), reconcileToken);
            return false;
        }
    }

    /** 只接受与原支付及本次退款金额一致的渠道结果，并在事务内结算一次。 */
    private void settleSuccess(RefundContext context, RefundGateway.Result result, boolean fromQuery) {
        settleSuccess(context, result, fromQuery, null);
    }

    /** 核对原支付与退款金额后，在订单锁下只结算一次并同步评价、库存和搜索。 */
    private void settleSuccess(RefundContext context, RefundGateway.Result result, boolean fromQuery,
                               String reconcileToken) {
        if (!context.payment().getOutTradeNo().equals(result.outTradeNo())
                || !context.payment().getAlipayTradeNo().equals(result.tradeNo())
                || result.amount() == null
                || result.amount().compareTo(context.attempt().getAmount()) != 0
                || (fromQuery && !context.attempt().getOutRequestNo().equals(result.outRequestNo()))) {
            throw new BusinessException(503, "支付宝退款结果与本地记录不匹配");
        }
        tx().executeWithoutResult(status -> {
            RefundAttempt expected = context.attempt();
            AfterSaleCase found = requireCase(expected.getCaseId());
            orders.selectForUpdate(found.getOrderId());
            AfterSaleCase sale = cases.selectForUpdate(expected.getCaseId());
            RefundAttempt current = refunds.selectForUpdate(expected.getId());
            if (reconcileToken != null && refunds.ownsReconciliation(expected.getId(), reconcileToken) != 1)
                throw new BusinessException(409, "退款核对租约已失效");
            if (current.getStatus() == RefundAttemptStatus.SUCCESS && sale.getStatus() == AfterSaleStatus.REFUNDED) return;
            if (sale.getStatus() != AfterSaleStatus.REFUNDING || current.getStatus() == RefundAttemptStatus.SUCCESS)
                throw new BusinessException(409, "退款状态冲突");
            LocalDateTime now = LocalDateTime.now(clock);
            if (refunds.resolve(current.getId(), RefundAttemptStatus.SUCCESS, now, null) != 1
                    || cases.markRefunded(sale.getId(), now) != 1)
                throw new BusinessException(409, "退款状态冲突");
            // 订单锁已串行化同单退款；评价行锁使隐藏、删除与退款只扣减汇总一次。
            for (AfterSaleItem line : items.selectForCase(sale.getId())) {
                ProductReview review = reviews.selectForUpdateByOrderItemId(line.getOrderItemId());
                if (review == null || !Boolean.TRUE.equals(review.getCountedInSummary())) continue;
                OrderItem original = orderItems.selectById(line.getOrderItemId());
                if (reviews.refundedQuantityForUpdate(line.getOrderItemId()) >= original.getQuantity()) {
                    if (reviews.excludeFromSummary(review.getId()) != 1
                            || reviews.removeFromSummary(review.getProductId(), review.getRating()) != 1)
                        throw new IllegalStateException("退款后评价汇总更新失败");
                }
            }
            if (sale.getType() == AfterSaleType.REFUND_ONLY
                    && shipments.selectByOrderId(sale.getOrderId()) == null) {
                restoreStock(sale.getId(), "REFUND_SUCCESS");
            } else {
                Set<Long> productIds = new HashSet<>();
                for (AfterSaleItem line : items.selectForCase(sale.getId())) {
                    OrderItem original = orderItems.selectById(line.getOrderItemId());
                    productIds.add(skus.selectById(original.getSkuId()).getProductId());
                }
                for (Long productId : productIds) {
                    if (outboxEventMapper.insertSearchProductRefresh(productId) != 1)
                        throw new IllegalStateException("商品搜索事件写入失败");
                }
            }
            audit.record(null, "REFUND_SUCCEEDED", sale.getId(), "支付宝退款成功", now);
        });
    }

    /** 将渠道结果不明的退款尝试留在待核对状态。 */
    private void markUnknown(Long attemptId) {
        markUnknown(attemptId, null);
    }

    /** 持有有效对账租约时更新未知状态与最后核对时间。 */
    private void markUnknown(Long attemptId, String reconcileToken) {
        tx().executeWithoutResult(status -> {
            if (reconcileToken != null) {
                refunds.selectForUpdate(attemptId);
                if (refunds.ownsReconciliation(attemptId, reconcileToken) != 1) return;
            }
            refunds.markUnknown(attemptId);
            refunds.markChecked(attemptId, LocalDateTime.now(clock));
        });
    }

    /** 分批领取未决退款并向渠道对账；限制单轮数量和耗时，异常留待下轮。 */
    @Override
    public void reconcileUnknown() {
        // UNKNOWN 表示结果未确认，不能据此再次生成退款单或判定失败。
        if (!gateway.configured()) return;
        LocalDateTime before = LocalDateTime.now(clock).minusSeconds(10);
        long started = System.nanoTime();
        int processed = 0;
        int failed = 0;
        for (Long id : refunds.unresolvedIds(before, 50)) {
            // A slow channel response must not turn this scan into an unbounded batch.
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis) break;
            String token = UUID.randomUUID().toString();
            if (refunds.claimReconciliation(id, token, before, RECONCILE_CLAIM_LEASE.toSeconds()) != 1) continue;
            processed++;
            try {
                RefundAttempt attempt = refunds.selectById(id);
                if (attempt == null || attempt.getStatus() == RefundAttemptStatus.SUCCESS) continue;
                AfterSaleCase sale = cases.selectById(attempt.getCaseId());
                if (sale == null || sale.getStatus() != AfterSaleStatus.REFUNDING) continue;
                PaymentAttempt paid = paidAttempt(orders.selectById(sale.getOrderId()));
                queryAndSettle(new RefundContext(attempt, paid, false), token);
            } catch (RuntimeException e) {
                failed++;
                log.warn("退款自动核对失败，attemptId={}", id, e);
                refunds.markCheckedClaimed(id, token, LocalDateTime.now(clock));
            } finally {
                refunds.releaseReconciliation(id, token);
            }
        }
        log.info("退款核对扫描 processed={} failed={} durationMs={} oldestPendingSeconds={}", processed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                refunds.oldestUnresolvedSeconds(before, LocalDateTime.now(clock)));
    }

    /** 返还售后涉及的 SKU 库存并写库存流水和商品搜索刷新事件。 */
    private void restoreStock(Long caseId, String operation) {
        Set<Long> changedProductIds = new HashSet<>();
        for (AfterSaleItem line : items.selectForCase(caseId)) {
            OrderItem original = orderItems.selectById(line.getOrderItemId());
            if (skus.increaseStock(original.getSkuId(), line.getQuantity()) != 1)
                throw new BusinessException(409, "库存返还失败");
            Integer balance = skus.selectStock(original.getSkuId());
            movements.insertRefundReturn(original.getSkuId(), original.getId(), line.getQuantity(), balance,
                    operation + ":" + caseId + ":" + line.getOrderItemId());
            changedProductIds.add(skus.selectById(original.getSkuId()).getProductId());
        }
        for (Long productId : changedProductIds) {
            if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
                throw new IllegalStateException("商品搜索事件写入失败");
            }
        }
    }

    /** 组合售后申请、明细、退货运单及退款记录供接口返回。 */
    private AfterSaleVO view(Long caseId) {
        AfterSaleCase sale = requireCase(caseId);
        List<AfterSaleVO.Item> lines = items.selectForCase(caseId).stream()
                .map(item -> new AfterSaleVO.Item(item.getOrderItemId(), item.getQuantity(), item.getRefundAmount()))
                .toList();
        ReturnShipment returned = returns.selectByCaseId(caseId);
        RefundAttempt attempt = refunds.selectLatest(caseId);
        return new AfterSaleVO(sale.getId(), sale.getOrderId(), sale.getType().name(), sale.getStatus().name(),
                sale.getReason(), sale.getRefundAmount(), sale.getRequestedAt(), sale.getFinishedAt(),
                sale.getReviewReason(), lines,
                returned == null ? null : new AfterSaleVO.ReturnInfo(returned.getCarrierName(),
                        returned.getTrackingNo(), returned.getSentAt(), returned.getReceivedAt()),
                attempt == null ? null : new AfterSaleVO.RefundInfo(attempt.getStatus().name(),
                        attempt.getOutRequestNo(), attempt.getRequestedAt(), attempt.getResolvedAt()));
    }

    /** 校验售后 ID 并读取申请，缺失时返回不存在。 */
    private AfterSaleCase requireCase(Long caseId) {
        requireId(caseId);
        AfterSaleCase sale = cases.selectById(caseId);
        if (sale == null) throw new BusinessException(404, "售后申请不存在");
        return sale;
    }

    /** 校验买家账号及订单归属，防止跨用户查看或修改售后。 */
    private Order buyerOrder(Long buyerId, Long orderId) {
        accountAccess.checkActive(buyerId);
        requireId(orderId);
        Order order = orders.selectById(orderId);
        if (order == null || !buyerId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        return order;
    }

    /** 校验店主与订单归属，供只读售后操作使用。 */
    private Order merchantOrder(Long merchantId, Long shopId, Long orderId) {
        return merchantOrder(merchantId, shopId, orderId, false);
    }

    /** 锁定店铺归属后校验订单，供售后状态写入使用。 */
    private Order merchantOrderForUpdate(Long merchantId, Long shopId, Long orderId) {
        return merchantOrder(merchantId, shopId, orderId, true);
    }

    /** 根据是否写入选择店铺归属锁，再确认订单属于该店。 */
    private Order merchantOrder(Long merchantId, Long shopId, Long orderId, boolean lock) {
        requireId(shopId);
        if (lock) merchantAccess.requireOwnedForUpdate(merchantId, shopId, "店铺不存在");
        else merchantAccess.requireOwned(merchantId, shopId, "店铺不存在");
        Order order = orders.selectById(orderId);
        if (order == null || !shopId.equals(order.getShopId())) throw new BusinessException(404, "订单不存在");
        return order;
    }

    /** 要求最近支付尝试成功且金额与订单一致，作为可退金额依据。 */
    private PaymentAttempt paidAttempt(Order order) {
        PaymentAttempt paid = payments.selectLatest(order.getId());
        if (paid == null || paid.getStatus() != PaymentAttemptStatus.SUCCESS
                || paid.getAlipayTradeNo() == null
                || paid.getAmount().compareTo(order.getTotalAmount()) != 0)
            throw new BusinessException(409, "订单支付记录异常");
        return paid;
    }

    /** 解析售后类型并拒绝未知枚举。 */
    private static AfterSaleType type(String value) {
        try {
            return AfterSaleType.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "售后类型无效");
        }
    }

    /** 清理并校验必填文本的长度。 */
    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty() || clean.length() > limit) throw new BusinessException(400, "文本不能为空或超长");
        return clean;
    }

    /** 清理可选文本，空白转为 null，超长则拒绝。 */
    private static String optionalText(String value, int limit) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > limit) throw new BusinessException(400, "文本超长");
        return clean.isEmpty() ? null : clean;
    }

    /** 校验业务 ID 为正数。 */
    private static void requireId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "ID必须大于0");
    }

    /** 为售后状态迁移建立短事务，外部退款调用在事务之外执行。 */
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private record RefundContext(RefundAttempt attempt, PaymentAttempt payment, boolean newAttempt) {}
}
