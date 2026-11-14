package com.shiwushop.trade.service.impl;

import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.vo.MarketingOrderSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.trade.vo.payment.PaymentStartVO;
import com.shiwushop.trade.vo.payment.PaymentStatusVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.payment.service.AlipayGateway;
import com.shiwushop.trade.vo.payment.PaymentCheckoutVO;
import com.shiwushop.trade.service.OrderPaymentService;
import com.shiwushop.trade.service.OrderRefundOperations;
import lombok.RequiredArgsConstructor;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.member.service.MemberRewardService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class OrderPaymentServiceImpl implements OrderPaymentService {
    /** 本类日志记录器，供业务事件或故障诊断使用。 */
    private static final Logger log = LoggerFactory.getLogger(OrderPaymentServiceImpl.class);
    /** 支付渠道查询结果中的交易已关闭状态。 */
    private static final String TRADE_CLOSED = "TRADE_CLOSED";
    /** 支付网关查询结果中的交易不存在状态，需结合订单与支付尝试判断后续处理。 */
    private static final String TRADE_NOT_FOUND = "NOT_FOUND";
    // 租约只决定何时允许接管；迟到结果仍由令牌和订单状态拒绝写回。
    /** 支付二维码创建任务的认领租期。 */
    private static final Duration QR_CLAIM_LEASE = Duration.ofMinutes(2);
    // Longer than a single query/close/cancel sequence with configured SDK timeouts.
    /** 超时未付款订单关单任务的认领租期。 */
    private static final Duration EXPIRY_CLAIM_LEASE = Duration.ofMinutes(2);
    private final AtomicBoolean cancellationFirst = new AtomicBoolean();
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentAttemptMapper paymentMapper;
    private final AlipayGateway gateway;
    private final ProductStockService stock;
    private final PlatformTransactionManager transactionManager;
    private final AccountAccess accountAccess;
    private final Clock businessClock;
    private final MemberRewardService memberRewards;
    private final MarketingOrderSettlement promotions;
    private final OrderRefundOperations afterSales;
    @Value("${shop.payment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    /** 零现金订单同事务停止履约；现金订单保存取消意图后再核对渠道。 */
    @Override
    public String cancelOrder(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        String state = new TransactionTemplate(transactionManager).execute(ignored -> {
            promotions.lockSource(orderId);
            Order order = orderMapper.selectForUpdate(orderId);
            if (order == null || !userId.equals(order.getUserId()))
                throw new BusinessException(404, "订单不存在");
            if (order.getStatus() == OrderStatus.CANCELLED)
                return "CANCELLED";
            if (order.getStatus() == OrderStatus.PAID
                    && ("ZERO_CASH".equals(order.getSettlementKind())
                    || "POINTS".equals(order.getSettlementKind()))) {
                if (!"STOPPED".equals(order.getFulfillmentState())) {
                    afterSales.cancelUnshippedZeroCash(order, orderItemMapper.selectForOrder(orderId));
                    if (orderMapper.stopZeroCashFulfillment(orderId) != 1)
                        throw new BusinessException(409, "订单履约状态冲突");
                    order.setFulfillmentState("STOPPED");
                }
                return "CANCELLED";
            }
            if (order.getStatus() != OrderStatus.UNPAID)
                throw new BusinessException(409, "订单不可取消");
            orderMapper.requestCancellation(orderId, LocalDateTime.now(businessClock));
            return "CANCEL_PENDING";
        });
        if ("CANCELLED".equals(state))
            return state;
        try {
            expireOrder(orderId);
        } catch (BusinessException e) {
            if (e.getCode() != 503)
                throw e;
            log.warn("订单 {} 取消核对暂不可用，将在下次扫描重试", orderId);
        }
        Order order = ownedOrder(userId, orderId);
        if (order.getStatus() == OrderStatus.CANCELLED)
            return "CANCELLED";
        if (order.getStatus() != OrderStatus.UNPAID)
            throw new BusinessException(409, "订单不可取消");
        return "CANCEL_PENDING";
    }

    /** 锁定买家订单，复用待支付尝试或创建新尝试，并返回收银页地址。 */
    @Override
    @Transactional
    public PaymentStartVO start(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        promotions.lockSource(orderId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId()))
            throw new BusinessException(404, "订单不存在");
        if (!isPayable(order)) {
            throw new BusinessException(409, "订单不可支付", Map.of("reason", "INVALID_STATE"), null);
        }
        requireGateway();
        PaymentAttempt attempt = paymentMapper.selectLatestForUpdate(orderId);
        if ("LOCAL_SIMULATION".equals(gateway.channel()) && attempt != null
                && !"LOCAL_SIMULATION".equals(attempt.getChannel()))
            throw new BusinessException(409, "正式渠道订单不能改用模拟支付");
        if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.CLOSED) {
            attempt = new PaymentAttempt();
            attempt.setOrderId(orderId);
            attempt.setChannel(gateway.channel());
            attempt.setOutTradeNo(("LOCAL_SIMULATION".equals(gateway.channel()) ? "LOCAL_SIMULATION" : "SHIWUSHOP")
                    + UUID.randomUUID().toString().replace("-", ""));
            attempt.setAppId(gateway.appId());
            attempt.setSellerId(gateway.sellerId());
            attempt.setAmount(order.getTotalAmount());
            attempt.setStatus(PaymentAttemptStatus.PENDING);
            attempt.setQrTimeoutGuaranteed(true);
            paymentMapper.insert(attempt);
        } else if (attempt.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(409, "支付记录状态异常");
        } else {
            requireCurrentGateway(attempt);
        }
        return new PaymentStartVO(attempt.getId(), orderId, attempt.getChannel(), attempt.getStatus().name(),
                "/payments/checkout/" + attempt.getOutTradeNo());
    }

    /** 获取或恢复二维码生成租约；渠道调用在数据库事务外进行，结果再回写。 */
    @Override
    public PaymentCheckoutVO checkoutPage(String outTradeNo) {
        PaymentAttempt attempt = paymentMapper.selectByOutTradeNo(outTradeNo);
        if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(404, "支付记录不存在");
        }
        QrCheckout checkout = new TransactionTemplate(transactionManager).execute(
                ignored -> claimCheckout(attempt));
        if (checkout.qrCode() != null) {
            return new PaymentCheckoutVO(checkout.qrCode(), checkout.amount(), checkout.payExpiresAt(),
                    gateway.channel());
        }
        if (checkout.recover()) {
            // 租约到期可能是渠道已受理但进程未保存二维码，先按原商户单号核对。
            AlipayGateway.TradeResult result = gateway.query(outTradeNo);
            if (!TRADE_NOT_FOUND.equals(result.status())) {
                if (paid(result.status())) {
                    confirmPaid(attempt, result);
                    throw new BusinessException(409, "订单已支付");
                }
                checkedResult(attempt, result);
                if (TRADE_CLOSED.equals(result.status())) {
                    markClosed(attempt);
                    throw new BusinessException(409, "支付记录已关闭");
                }
                if (!"WAIT_BUYER_PAY".equals(result.status())) {
                    throw new BusinessException(503, "支付状态待核对");
                }
            }
        }
        AlipayGateway.QrCode qrCode = gateway.qrCode(outTradeNo, checkout.amount(), checkout.payExpiresAt());
        QrCheckout saved = new TransactionTemplate(transactionManager).execute(
                ignored -> saveCheckoutQr(attempt, checkout.token(), qrCode));
        return new PaymentCheckoutVO(saved.qrCode(), saved.amount(), saved.payExpiresAt(), gateway.channel());
    }

    /** 锁定订单与最新支付尝试，复用有效二维码或领取一次生成租约。 */
    private QrCheckout claimCheckout(PaymentAttempt attempt) {
        promotions.lockSource(attempt.getOrderId());
        Order order = orderMapper.selectForUpdate(attempt.getOrderId());
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(attempt.getOrderId());
        if (!isPayable(order) || latest == null || !attempt.getId().equals(latest.getId())
                || latest.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(409, "订单不可支付");
        }
        requireGateway();
        requireCurrentGateway(latest);
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (latest.getQrCode() != null) {
            if (latest.getQrExpiresAt() == null || !now.isBefore(latest.getQrExpiresAt())) {
                throw new BusinessException(409, "二维码已过期，请重新下单");
            }
            return new QrCheckout(latest.getQrCode(), latest.getAmount(), order.getPayExpiresAt(), null, false);
        }
        boolean recover = latest.getQrClaimToken() != null;
        String token = UUID.randomUUID().toString();
        if (paymentMapper.claimQrCode(latest.getId(), token, now.plus(QR_CLAIM_LEASE), now) != 1) {
            throw new BusinessException(409, "二维码生成中，请稍后重试");
        }
        return new QrCheckout(null, latest.getAmount(), order.getPayExpiresAt(), token, recover);
    }

    /** 核对租约、订单和二维码有效期后保存渠道结果，拒绝迟到写回。 */
    private QrCheckout saveCheckoutQr(PaymentAttempt attempt, String token, AlipayGateway.QrCode qrCode) {
        promotions.lockSource(attempt.getOrderId());
        Order order = orderMapper.selectForUpdate(attempt.getOrderId());
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(attempt.getOrderId());
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (!isPayable(order)) {
            throw new BusinessException(409, "订单不可支付", Map.of("reason", "INVALID_STATE"), null);
        }
        if (latest == null || !attempt.getId().equals(latest.getId())
                || latest.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(409, "支付记录状态冲突");
        }
        if (!token.equals(latest.getQrClaimToken()) || latest.getQrClaimExpiresAt() == null
                || !now.isBefore(latest.getQrClaimExpiresAt())) {
            throw new BusinessException(409, "支付记录状态冲突");
        }
        if (qrCode == null || qrCode.value() == null || qrCode.value().isBlank()) {
            throw new BusinessException(503, "支付宝二维码结果无效");
        }
        LocalDateTime qrExpiresAt = qrCode.expiresAt();
        if (qrExpiresAt == null || !now.isBefore(qrExpiresAt)
                || qrExpiresAt.isAfter(order.getPayExpiresAt())) {
            throw new BusinessException(503, "支付宝二维码有效期无效");
        }
        if (paymentMapper.storeQrCode(attempt.getId(), qrCode.value(), qrExpiresAt, token) != 1) {
            throw new BusinessException(409, "支付记录状态冲突");
        }
        return new QrCheckout(qrCode.value(), latest.getAmount(), order.getPayExpiresAt(), null, false);
    }

    private record QrCheckout(String qrCode, BigDecimal amount, LocalDateTime payExpiresAt,
                              String token, boolean recover) {
    }

    /** 返回当前买家订单及最近一次支付尝试状态，不请求支付渠道。 */
    @Override
    public PaymentStatusVO status(Long userId, Long orderId) {
        Order order = ownedOrder(userId, orderId);
        return statusOf(order);
    }

    /** 对待支付尝试主动查渠道并同步结果，再返回最新订单状态。 */
    @Override
    public PaymentStatusVO sync(Long userId, Long orderId) {
        Order order = ownedOrder(userId, orderId);
        if (order.getPayExpiresAt() == null)
            throw new BusinessException(409, "历史订单不可同步支付");
        PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
        if (attempt == null)
            throw new BusinessException(409, "尚未发起支付");
        if (attempt.getStatus() == PaymentAttemptStatus.PENDING) {
            requireGateway();
            requireCurrentGateway(attempt);
            applyQueryResult(attempt, gateway.query(attempt.getOutTradeNo()));
        }
        return statusOf(ownedOrder(userId, orderId));
    }

    /** 仅本地模拟渠道允许显式确认；查询和创建支付尝试都不会调用此方法。 */
    @Override
    public PaymentStatusVO simulate(Long userId, Long orderId) {
        ownedOrder(userId, orderId);
        if (!"LOCAL_SIMULATION".equals(gateway.channel()))
            throw new BusinessException(404, "本地模拟支付未启用");
        PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
        if (attempt == null)
            throw new BusinessException(409, "请先创建模拟支付单");
        requireCurrentGateway(attempt);
        confirmPaid(attempt, new AlipayGateway.TradeResult("TRADE_SUCCESS", attempt.getOutTradeNo(),
                "LOCAL_SIMULATION_" + attempt.getId(), attempt.getAmount()));
        return statusOf(ownedOrder(userId, orderId));
    }

    /** 验签并核对商户、商户单号与金额，按支付或关闭通知更新订单。 */
    @Override
    public void notifyPaid(Map<String, String> form) {
        // 回调必须先验签，再核对商户身份、订单号和金额，不能仅凭“支付成功”字段入账。
        requireGateway();
        if (!gateway.verify(form))
            throw new BusinessException(400, "支付通知验签失败");
        String outTradeNo = form.get("out_trade_no");
        PaymentAttempt attempt = outTradeNo == null ? null : paymentMapper.selectByOutTradeNo(outTradeNo);
        if (attempt == null || !currentGateway(attempt)
                || !attempt.getAppId().equals(form.get("app_id"))
                || !attempt.getSellerId().equals(form.get("seller_id"))
                || !gateway.appId().equals(form.get("app_id"))
                || !gateway.sellerId().equals(form.get("seller_id"))
                || !sameAmount(attempt.getAmount(), form.get("total_amount"))) {
            throw new BusinessException(400, "支付通知不匹配");
        }
        String tradeStatus = form.get("trade_status");
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            confirmPaid(attempt, new AlipayGateway.TradeResult(tradeStatus, outTradeNo,
                    form.get("trade_no"), attempt.getAmount()));
        } else if (TRADE_CLOSED.equals(tradeStatus)) {
            markClosed(attempt);
        } else {
            throw new BusinessException(400, "支付通知状态无效");
        }
    }

    /** 每轮扫描最多 50 个过期订单，在时间预算内逐个核对并记录处理量。 */
    @Override
    public void expireDueOrders() {
        long started = System.nanoTime();
        int processed = 0;
        int failed = 0;
        List<Long> expired = orderMapper.selectExpiredIds(LocalDateTime.now(businessClock), 25);
        List<Long> requested = orderMapper.selectCancellationIds(25);
        List<Long> candidates = new ArrayList<>(50);
        Set<Long> seen = new HashSet<>();
        boolean first = cancellationFirst.getAndSet(!cancellationFirst.get());
        for (int i = 0; i < Math.max(expired.size(), requested.size()); i++) {
            List<Long> left = first ? requested : expired;
            List<Long> right = first ? expired : requested;
            if (i < left.size() && seen.add(left.get(i)))
                candidates.add(left.get(i));
            if (i < right.size() && seen.add(right.get(i)))
                candidates.add(right.get(i));
        }
        for (Long orderId : candidates) {
            // Finish the current order, but leave new work for the next scan after the budget.
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis)
                break;
            processed++;
            try {
                expireOrder(orderId);
            } catch (BusinessException e) {
                failed++;
                log.warn("订单 {} 到期核对失败，将在下次扫描重试：{}", orderId, e.getMessage());
            }
        }
        for (Long orderId : orderMapper.selectFailedGroupRefundIds(25)) {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis)
                break;
            processed++;
            try {
                refundFailedGroup(orderId);
            } catch (BusinessException e) {
                failed++;
                log.warn("失败团订单 {} 退款待下轮重试：{}", orderId, e.getMessage());
            }
        }
        log.info("支付到期扫描 processed={} failed={} durationMs={} oldestPendingSeconds={}", processed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                orderMapper.oldestExpiredSeconds(LocalDateTime.now(businessClock)));
    }

    /** 接管成团失败前已付款的订单，以及提交退款责任后中断的迟付回调。 */
    private void refundFailedGroup(Long orderId) {
        Long saleId = new TransactionTemplate(transactionManager).execute(ignored -> {
            MarketingOrderSource source = promotions.lockSource(orderId);
            Order order = orderMapper.selectForUpdate(orderId);
            if (source == null || source.groupId() == null || order == null
                    || order.getStatus() != OrderStatus.PAID
                    || !"FAILED".equals(orderMapper.groupStatus(source.groupId())))
                return null;
            return afterSales.prepareFailedGroupRefund(order, orderItemMapper.selectForOrder(orderId));
        });
        if (saleId != null)
            afterSales.refundFailedGroup(saleId);
    }

    /** 领取单笔订单的到期处理租约，完成后按令牌释放以防并发扫描。 */
    @Override
    public void expireOrder(Long orderId) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (orderMapper.claimExpiry(orderId, token, now, EXPIRY_CLAIM_LEASE.toSeconds()) != 1)
            return;
        try {
            expireClaimedOrder(orderId, token);
        } finally {
            orderMapper.releaseExpiry(orderId, token);
        }
    }

    /** 先查询或关闭渠道交易；只有确认未支付且已关闭时才取消订单。 */
    private void expireClaimedOrder(Long orderId, String token) {
        // 到期不等于渠道已关闭；未知支付结果必须留待下次扫描，不能直接归还库存。
        Order order = orderMapper.selectById(orderId);
        if (!isCancellationDue(order))
            return;
        PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
        if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.CLOSED) {
            cancel(orderId, attempt, false, token);
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.PENDING)
            return;
        if (!gateway.configured())
            return;
        if (!currentGateway(attempt)) {
            log.warn("订单 {} 的支付渠道与当前配置不一致，保留待核对状态", orderId);
            return;
        }
        AlipayGateway.TradeResult result = gateway.query(attempt.getOutTradeNo());
        if (paid(result.status())) {
            confirmPaid(attempt, result, token);
            return;
        }
        if (!TRADE_NOT_FOUND.equals(result.status()))
            checkedResult(attempt, result);
        if (TRADE_CLOSED.equals(result.status())) {
            cancel(orderId, attempt, true, token);
        } else if ("WAIT_BUYER_PAY".equals(result.status())
                && gateway.close(attempt.getOutTradeNo())) {
            cancel(orderId, attempt, true, token);
        } else if (TRADE_NOT_FOUND.equals(result.status())
                && isExpiredUnpaid(order) && Boolean.TRUE.equals(attempt.getQrTimeoutGuaranteed())
                && (attempt.getQrClaimToken() == null || attempt.getQrExpiresAt() != null)
                && (attempt.getQrExpiresAt() == null ||
                !LocalDateTime.now(businessClock).isBefore(attempt.getQrExpiresAt()))) {
            cancel(orderId, attempt, true, token);
        }
    }

    /** 根据渠道查询结果确认支付或关闭尝试，未知状态留待后续核对。 */
    private void applyQueryResult(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        if (paid(result.status())) {
            confirmPaid(attempt, result);
            return;
        }
        if (!TRADE_NOT_FOUND.equals(result.status()))
            checkedResult(attempt, result);
        if (TRADE_CLOSED.equals(result.status())) {
            markClosed(attempt);
        }
    }

    /** 普通回调或主动同步的支付确认入口，不要求持有到期扫描租约。 */
    private void confirmPaid(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        confirmPaid(attempt, result, null);
    }

    /** 核对交易身份与成功状态后，在短事务内完成支付状态迁移。 */
    private void confirmPaid(PaymentAttempt attempt, AlipayGateway.TradeResult result, String expiryToken) {
        checkedResult(attempt, result);
        if (!paid(result.status()) || result.tradeNo() == null || result.tradeNo().isBlank()) {
            throw new BusinessException(400, "支付结果无效");
        }
        Long failedGroupSale = new TransactionTemplate(transactionManager).execute(
                ignored -> confirmPaidInTransaction(attempt, result.tradeNo(), expiryToken));
        if (failedGroupSale != null)
            afterSales.refundFailedGroup(failedGroupSale);
    }

    /** 回调、主动同步和到期扫描在订单行锁下竞争，只允许一次最终状态迁移。 */
    private Long confirmPaidInTransaction(PaymentAttempt attempt, String tradeNo, String expiryToken) {
        MarketingOrderSource source = promotions.lockSource(attempt.getOrderId());
        Order order = orderMapper.selectForUpdate(attempt.getOrderId());
        if (expiryToken != null && orderMapper.ownsExpiry(attempt.getOrderId(), expiryToken) != 1)
            return null;
        PaymentAttempt current = paymentMapper.selectLatestForUpdate(attempt.getOrderId());
        if (order == null || current == null || !attempt.getId().equals(current.getId()))
            throw new BusinessException(409, "支付记录不存在");
        if (current.getStatus() == PaymentAttemptStatus.SUCCESS && order.getStatus() == OrderStatus.PAID
                && tradeNo.equals(current.getAlipayTradeNo()))
            return source != null && source.groupId() != null
                    && "FAILED".equals(orderMapper.groupStatus(source.groupId()))
                    ? afterSales.prepareFailedGroupRefund(order, orderItemMapper.selectForOrder(order.getId())) : null;
        if (order.getStatus() != OrderStatus.UNPAID || current.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(409, "订单支付状态冲突");
        }
        if ("LOCAL_SIMULATION".equals(current.getChannel()) && !isPayable(order))
            throw new BusinessException(409, "订单已取消、正在取消或已过期，不能模拟付款");
        LocalDateTime paidAt = LocalDateTime.now(businessClock);
        if (paymentMapper.markSuccess(current.getId(), tradeNo, paidAt) != 1
                || orderMapper.markPaid(order.getId(), paidAt) != 1) {
            throw new BusinessException(409, "订单支付状态冲突");
        }
        if ("LOCAL_SIMULATION".equals(current.getChannel()))
            paymentMapper.auditSimulation(order.getUserId(), order.getId(), current.getId(), paidAt);
        List<OrderItem> items = orderItemMapper.selectForOrder(order.getId());
        memberRewards.paymentConfirmed(order.getUserId(), order.getId(), paidAt,
                orderItemMapper.rewardLines(order.getId()));
        for (OrderItem item : items) {
            stock.recordSale(item.getProductId(), item.getQuantity());
        }
        if (Integer.valueOf(1).equals(order.getPricingVersion()))
            promotions.finish(order.getId(), order.getUserId(), true);
        if (source != null && source.groupId() != null)
            orderMapper.releaseSucceededGroup(source.groupId());
        Long failedGroupSale = source != null && source.groupId() != null
                && "FAILED".equals(orderMapper.groupStatus(source.groupId()))
                ? afterSales.prepareFailedGroupRefund(order, items) : null;
        queueProductRefreshes(items);
        BusinessEventLog.committed("trade", "order_paid", "order", order.getId());
        return failedGroupSale;
    }

    /** 锁定订单并把仍待支付的尝试标记关闭；已支付订单不受影响。 */
    private void markClosed(PaymentAttempt attempt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            promotions.lockSource(attempt.getOrderId());
            Order order = orderMapper.selectForUpdate(attempt.getOrderId());
            PaymentAttempt current = paymentMapper.selectById(attempt.getId());
            if (order == null || current == null || order.getStatus() == OrderStatus.PAID)
                return;
            if (current.getStatus() == PaymentAttemptStatus.PENDING)
                paymentMapper.markClosed(current.getId());
        });
    }

    /** 在独立事务中再次核对取消条件并归还库存。 */
    private void cancel(Long orderId, PaymentAttempt expected, boolean confirmedClosed, String expiryToken) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                ignored -> cancelInTransaction(orderId, expected, confirmedClosed, expiryToken));
    }

    /** 再次读取最新支付尝试，防止渠道查询期间出现新的支付或二维码。 */
    private void cancelInTransaction(Long orderId, PaymentAttempt expected, boolean confirmedClosed,
                                     String expiryToken) {
        promotions.lockSource(orderId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (orderMapper.ownsExpiry(orderId, expiryToken) != 1)
            return;
        if (!isCancellationDue(order))
            return;
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(orderId);
        if (!matchesExpectedAttempt(latest, expected) || !closeForCancellation(latest, confirmedClosed))
            return;
        if (orderMapper.markCancelled(orderId) != 1)
            return;
        restoreStock(order);
        if (Integer.valueOf(1).equals(order.getPricingVersion()))
            promotions.finish(orderId, order.getUserId(), false);
        BusinessEventLog.committed("trade", "order_cancelled", "order", orderId);
    }

    /** 确认渠道核对期间没有产生另一笔支付尝试。 */
    private boolean matchesExpectedAttempt(PaymentAttempt latest, PaymentAttempt expected) {
        return expected == null ? latest == null : latest != null && expected.getId().equals(latest.getId());
    }

    /** 仅在渠道已确认关闭时关闭待支付尝试，供订单取消使用。 */
    private boolean closeForCancellation(PaymentAttempt latest, boolean confirmedClosed) {
        if (latest == null || latest.getStatus() == PaymentAttemptStatus.CLOSED)
            return true;
        if (latest.getStatus() != PaymentAttemptStatus.PENDING || !confirmedClosed)
            return false;
        if (paymentMapper.markClosed(latest.getId()) != 1) {
            throw new BusinessException(409, "支付记录状态冲突");
        }
        return true;
    }

    /** 逐项返还已取消订单的库存，写库存流水并触发搜索刷新。 */
    private void restoreStock(Order order) {
        List<OrderItem> items = orderItemMapper.selectForOrder(order.getId());
        if (items.isEmpty())
            throw new BusinessException(409, "订单明细缺失");
        if ("NORMAL".equals(order.getBusinessType()))
            for (OrderItem item : items)
                stock.returnCancelledOrder(item.getSkuId(), item.getId(), item.getQuantity(),
                        "ORDER_CANCEL_RETURN:" + order.getId() + ":" + item.getId());
        queueProductRefreshes(items);
    }

    /** 为受库存或销量变化影响的商品各写一条搜索刷新事件。 */
    private void queueProductRefreshes(List<OrderItem> items) {
        stock.refreshProducts(items.stream().map(OrderItem::getProductId).toList());
    }

    /** 仅未支付且仍在支付截止时间内的订单允许发起支付。 */
    private boolean isPayable(Order order) {
        return order != null && order.getStatus() == OrderStatus.UNPAID && order.getCancelRequestedAt() == null
                && order.getPayExpiresAt() != null &&
                LocalDateTime.now(businessClock).isBefore(order.getPayExpiresAt());
    }

    /** 取消请求和自然到期共享持久化租约。 */
    private boolean isCancellationDue(Order order) {
        return order != null && order.getStatus() == OrderStatus.UNPAID
                && (order.getCancelRequestedAt() != null || isExpiredUnpaid(order));
    }

    /** 判断订单是否未支付且已到支付截止时间。 */
    private boolean isExpiredUnpaid(Order order) {
        return order != null && order.getStatus() == OrderStatus.UNPAID && order.getPayExpiresAt() != null
                && !LocalDateTime.now(businessClock).isBefore(order.getPayExpiresAt());
    }

    /** 核对渠道查询返回的商户单号和金额，防止错误交易入账。 */
    private void checkedResult(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        if (!attempt.getOutTradeNo().equals(result.outTradeNo())
                || result.amount() == null || attempt.getAmount().compareTo(result.amount()) != 0) {
            throw new BusinessException(400, "支付宝交易信息不匹配");
        }
    }

    /** 按十进制金额比较通知金额，格式非法时视为不匹配。 */
    private boolean sameAmount(BigDecimal expected, String value) {
        if (value == null)
            return false;
        try {
            return expected.compareTo(new BigDecimal(value)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 识别渠道的两种支付成功状态。 */
    private boolean paid(String status) {
        return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status);
    }

    /** 组合订单状态与最近支付尝试，供买家轮询。 */
    private PaymentStatusVO statusOf(Order order) {
        PaymentAttempt attempt = paymentMapper.selectLatest(order.getId());
        return new PaymentStatusVO(order.getId(), order.getStatus().name(), order.getPayExpiresAt(),
                attempt == null ? null : new PaymentStatusVO.Payment(attempt.getId(),
                        attempt.getChannel(), attempt.getStatus().name(), attempt.getPaidAt()));
    }

    /** 按买家 ID 查询有效账号的订单，隐藏其他用户的订单。 */
    private Order ownedOrder(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId).eq(Order::getUserId, userId));
        if (order == null)
            throw new BusinessException(404, "订单不存在");
        return order;
    }

    /** 渠道未配置时拒绝需要与支付平台交互的操作。 */
    private void requireGateway() {
        if (!gateway.configured())
            throw new BusinessException(503, "支付渠道未配置");
    }

    /** 阻止旧商户配置下的支付尝试使用当前渠道配置继续处理。 */
    private void requireCurrentGateway(PaymentAttempt attempt) {
        if (!currentGateway(attempt))
            throw new BusinessException(409, "支付渠道配置已变化");
    }

    /** 对比支付尝试记录的渠道、应用和商户身份。 */
    private boolean currentGateway(PaymentAttempt attempt) {
        return gateway.channel().equals(attempt.getChannel())
                && gateway.appId().equals(attempt.getAppId())
                && gateway.sellerId().equals(attempt.getSellerId());
    }
}
