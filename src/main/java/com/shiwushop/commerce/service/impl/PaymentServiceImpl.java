package com.shiwushop.commerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.commerce.vo.payment.PaymentStartVO;
import com.shiwushop.commerce.vo.payment.PaymentStatusVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.order.OrderItem;
import com.shiwushop.commerce.entity.payment.PaymentAttempt;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.entity.status.PaymentAttemptStatus;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import com.shiwushop.commerce.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.payment.PaymentQrPage;
import com.shiwushop.commerce.service.PaymentService;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String TRADE_CLOSED = "TRADE_CLOSED";
    private static final String TRADE_NOT_FOUND = "NOT_FOUND";
    // 租约只决定何时允许接管；迟到结果仍由令牌和订单状态拒绝写回。
    private static final Duration QR_CLAIM_LEASE = Duration.ofMinutes(2);
    // Longer than a single query/close/cancel sequence with configured SDK timeouts.
    private static final Duration EXPIRY_CLAIM_LEASE = Duration.ofMinutes(2);
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentAttemptMapper paymentMapper;
    private final SkuMapper skuMapper;
    private final ProductMapper productMapper;
    private final InventoryMovementMapper inventoryMovementMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final AlipayGateway gateway;
    private final PlatformTransactionManager transactionManager;
    private final AccountAccess accountAccess;
    private final Clock businessClock;
    @Value("${shop.payment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    @Override
    @Transactional
    public PaymentStartVO start(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectForUpdate(orderId);
        if (order == null || !userId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        if (!isPayable(order)) {
            throw new BusinessException(409, "订单不可支付");
        }
        requireGateway();
        PaymentAttempt attempt = paymentMapper.selectLatestForUpdate(orderId);
        if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.CLOSED) {
            attempt = new PaymentAttempt();
            attempt.setOrderId(orderId);
            attempt.setChannel(gateway.channel());
            attempt.setOutTradeNo("SHIWUSHOP" + UUID.randomUUID().toString().replace("-", ""));
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

    @Override
    public String checkoutPage(String outTradeNo) {
        PaymentAttempt attempt = paymentMapper.selectByOutTradeNo(outTradeNo);
        if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.PENDING) {
            throw new BusinessException(404, "支付记录不存在");
        }
        QrCheckout checkout = new TransactionTemplate(transactionManager).execute(
                ignored -> claimCheckout(attempt));
        if (checkout.qrCode() != null) {
            return PaymentQrPage.render(checkout.qrCode(), checkout.amount(), checkout.payExpiresAt(),
                    gateway.channel());
        }
        if (checkout.recover()) {
            // 租约到期可能是渠道已受理但进程未保存二维码，先按原商户单号核对。
            AlipayGateway.TradeResult result = gateway.query(outTradeNo);
            if (!TRADE_NOT_FOUND.equals(result.status())) {
                checkedResult(attempt, result);
                if (paid(result.status())) {
                    confirmPaid(attempt, result);
                    throw new BusinessException(409, "订单已支付");
                }
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
        return PaymentQrPage.render(saved.qrCode(), saved.amount(), saved.payExpiresAt(), gateway.channel());
    }

    private QrCheckout claimCheckout(PaymentAttempt attempt) {
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

    private QrCheckout saveCheckoutQr(PaymentAttempt attempt, String token, AlipayGateway.QrCode qrCode) {
        Order order = orderMapper.selectForUpdate(attempt.getOrderId());
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(attempt.getOrderId());
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (!isPayable(order)) {
            throw new BusinessException(409, "订单不可支付");
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
                              String token, boolean recover) {}

    @Override
    public PaymentStatusVO status(Long userId, Long orderId) {
        Order order = ownedOrder(userId, orderId);
        return statusOf(order);
    }

    @Override
    public PaymentStatusVO sync(Long userId, Long orderId) {
        Order order = ownedOrder(userId, orderId);
        if (order.getPayExpiresAt() == null) throw new BusinessException(409, "历史订单不可同步支付");
        PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
        if (attempt == null) throw new BusinessException(409, "尚未发起支付");
        if (attempt.getStatus() == PaymentAttemptStatus.PENDING) {
            requireGateway();
            requireCurrentGateway(attempt);
            applyQueryResult(attempt, gateway.query(attempt.getOutTradeNo()));
        }
        return statusOf(ownedOrder(userId, orderId));
    }

    @Override
    public void notifyPaid(Map<String, String> form) {
        // 回调必须先验签，再核对商户身份、订单号和金额，不能仅凭“支付成功”字段入账。
        requireGateway();
        if (!gateway.verify(form)) throw new BusinessException(400, "支付通知验签失败");
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

    @Override
    public void expireDueOrders() {
        long started = System.nanoTime();
        int processed = 0;
        int failed = 0;
        for (Long orderId : orderMapper.selectExpiredIds(LocalDateTime.now(businessClock), 50)) {
            // Finish the current order, but leave new work for the next scan after the budget.
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis) break;
            processed++;
            try {
                expireOrder(orderId);
            } catch (BusinessException e) {
                failed++;
                log.warn("订单 {} 到期核对失败，将在下次扫描重试：{}", orderId, e.getMessage());
            }
        }
        log.info("支付到期扫描 processed={} failed={} durationMs={} oldestPendingSeconds={}", processed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                orderMapper.oldestExpiredSeconds(LocalDateTime.now(businessClock)));
    }

    @Override
    public void expireOrder(Long orderId) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(businessClock);
        if (orderMapper.claimExpiry(orderId, token, now, EXPIRY_CLAIM_LEASE.toSeconds()) != 1) return;
        try {
            expireClaimedOrder(orderId, token);
        } finally {
            orderMapper.releaseExpiry(orderId, token);
        }
    }

    private void expireClaimedOrder(Long orderId, String token) {
        // 到期不等于渠道已关闭；未知支付结果必须留待下次扫描，不能直接归还库存。
        Order order = orderMapper.selectById(orderId);
        if (!isExpiredUnpaid(order)) return;
        PaymentAttempt attempt = paymentMapper.selectLatest(orderId);
        if (attempt == null || attempt.getStatus() == PaymentAttemptStatus.CLOSED) {
            cancel(orderId, attempt, false, token);
            return;
        }
        if (attempt.getStatus() != PaymentAttemptStatus.PENDING) return;
        if (!gateway.configured()) return;
        if (!currentGateway(attempt)) {
            log.warn("订单 {} 的支付渠道与当前配置不一致，保留待核对状态", orderId);
            return;
        }
        AlipayGateway.TradeResult result = gateway.query(attempt.getOutTradeNo());
        if (!TRADE_NOT_FOUND.equals(result.status())) checkedResult(attempt, result);
        if (paid(result.status())) {
            confirmPaid(attempt, result, token);
        } else if (TRADE_CLOSED.equals(result.status())) {
            checkedResult(attempt, result);
            cancel(orderId, attempt, true, token);
        } else if ("WAIT_BUYER_PAY".equals(result.status())
                && gateway.close(attempt.getOutTradeNo())) {
            cancel(orderId, attempt, true, token);
        } else if (TRADE_NOT_FOUND.equals(result.status())) {
            AlipayGateway.CancelResult cancelled = gateway.cancel(attempt.getOutTradeNo());
            if (cancelled == AlipayGateway.CancelResult.CLOSED
                    || (cancelled == AlipayGateway.CancelResult.NO_TRADE
                    && Boolean.TRUE.equals(attempt.getQrTimeoutGuaranteed()))) {
                cancel(orderId, attempt, true, token);
            }
        }
    }

    private void applyQueryResult(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        if (!TRADE_NOT_FOUND.equals(result.status())) checkedResult(attempt, result);
        if (paid(result.status())) confirmPaid(attempt, result);
        else if (TRADE_CLOSED.equals(result.status())) {
            markClosed(attempt);
        }
    }

    private void confirmPaid(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        confirmPaid(attempt, result, null);
    }

    private void confirmPaid(PaymentAttempt attempt, AlipayGateway.TradeResult result, String expiryToken) {
        checkedResult(attempt, result);
        if (!paid(result.status()) || result.tradeNo() == null || result.tradeNo().isBlank()) {
            throw new BusinessException(400, "支付结果无效");
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(
                ignored -> confirmPaidInTransaction(attempt, result.tradeNo(), expiryToken));
    }

    /** 回调、主动同步和到期扫描在订单行锁下竞争，只允许一次最终状态迁移。 */
    private void confirmPaidInTransaction(PaymentAttempt attempt, String tradeNo, String expiryToken) {
        Order order = orderMapper.selectForUpdate(attempt.getOrderId());
        if (expiryToken != null && orderMapper.ownsExpiry(attempt.getOrderId(), expiryToken) != 1) return;
        PaymentAttempt current = paymentMapper.selectById(attempt.getId());
        if (order == null || current == null) throw new BusinessException(409, "支付记录不存在");
        if (current.getStatus() == PaymentAttemptStatus.SUCCESS && order.getStatus() == OrderStatus.PAID
                && tradeNo.equals(current.getAlipayTradeNo())) return;
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(order.getId());
        if (order.getStatus() != OrderStatus.UNPAID || current.getStatus() != PaymentAttemptStatus.PENDING
                || latest == null || !current.getId().equals(latest.getId())) {
            throw new BusinessException(409, "订单支付状态冲突");
        }
        LocalDateTime paidAt = LocalDateTime.now(businessClock);
        if (paymentMapper.markSuccess(current.getId(), tradeNo, paidAt) != 1
                || orderMapper.markPaid(order.getId(), paidAt) != 1) {
            throw new BusinessException(409, "订单支付状态冲突");
        }
        List<OrderItem> items = orderItemMapper.selectForOrder(order.getId());
        for (OrderItem item : items) {
            productMapper.increaseSoldCount(item.getProductId(), item.getQuantity());
        }
        queueProductRefreshes(items);
    }

    private void markClosed(PaymentAttempt attempt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Order order = orderMapper.selectForUpdate(attempt.getOrderId());
            PaymentAttempt current = paymentMapper.selectById(attempt.getId());
            if (order == null || current == null || order.getStatus() == OrderStatus.PAID) return;
            if (current.getStatus() == PaymentAttemptStatus.PENDING) paymentMapper.markClosed(current.getId());
        });
    }

    private void cancel(Long orderId, PaymentAttempt expected, boolean confirmedClosed, String expiryToken) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                ignored -> cancelInTransaction(orderId, expected, confirmedClosed, expiryToken));
    }

    /** 再次读取最新支付尝试，防止渠道查询期间出现新的支付或二维码。 */
    private void cancelInTransaction(Long orderId, PaymentAttempt expected, boolean confirmedClosed,
                                     String expiryToken) {
        Order order = orderMapper.selectForUpdate(orderId);
        if (orderMapper.ownsExpiry(orderId, expiryToken) != 1) return;
        if (!isExpiredUnpaid(order)) return;
        PaymentAttempt latest = paymentMapper.selectLatestForUpdate(orderId);
        if (latest != null && latest.getStatus() == PaymentAttemptStatus.PENDING
                && latest.getQrExpiresAt() != null
                && LocalDateTime.now(businessClock).isBefore(latest.getQrExpiresAt().plusMinutes(1))) return;
        if (!matchesExpectedAttempt(latest, expected) || !closeForCancellation(latest, confirmedClosed)) return;
        if (orderMapper.markCancelled(orderId) != 1) return;
        restoreStock(orderId);
    }

    private boolean matchesExpectedAttempt(PaymentAttempt latest, PaymentAttempt expected) {
        return expected == null ? latest == null : latest != null && expected.getId().equals(latest.getId());
    }

    private boolean closeForCancellation(PaymentAttempt latest, boolean confirmedClosed) {
        if (latest == null || latest.getStatus() == PaymentAttemptStatus.CLOSED) return true;
        if (latest.getStatus() != PaymentAttemptStatus.PENDING || !confirmedClosed) return false;
        if (paymentMapper.markClosed(latest.getId()) != 1) {
            throw new BusinessException(409, "支付记录状态冲突");
        }
        return true;
    }

    private void restoreStock(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectForOrder(orderId);
        if (items.isEmpty()) throw new BusinessException(409, "订单明细缺失");
        for (OrderItem item : items) {
            if (skuMapper.increaseStock(item.getSkuId(), item.getQuantity()) != 1) {
                throw new BusinessException(409, "库存归还失败");
            }
            Integer balance = skuMapper.selectStock(item.getSkuId());
            inventoryMovementMapper.insertOrderCancelReturn(item.getSkuId(), item.getId(), item.getQuantity(), balance,
                    "ORDER_CANCEL_RETURN:" + orderId + ":" + item.getId());
        }
        queueProductRefreshes(items);
    }

    private void queueProductRefreshes(List<OrderItem> items) {
        items.stream().map(OrderItem::getProductId).distinct().forEach(productId -> {
            if (outboxEventMapper.insertSearchProductRefresh(productId) != 1) {
                throw new IllegalStateException("商品搜索事件写入失败");
            }
        });
    }

    private boolean isPayable(Order order) {
        return order != null && order.getStatus() == OrderStatus.UNPAID && order.getPayExpiresAt() != null
                && LocalDateTime.now(businessClock).isBefore(order.getPayExpiresAt());
    }

    private boolean isExpiredUnpaid(Order order) {
        return order != null && order.getStatus() == OrderStatus.UNPAID && order.getPayExpiresAt() != null
                && !LocalDateTime.now(businessClock).isBefore(order.getPayExpiresAt());
    }

    private void checkedResult(PaymentAttempt attempt, AlipayGateway.TradeResult result) {
        if (!attempt.getOutTradeNo().equals(result.outTradeNo())
                || result.amount() == null || attempt.getAmount().compareTo(result.amount()) != 0) {
            throw new BusinessException(400, "支付宝交易信息不匹配");
        }
    }

    private boolean sameAmount(BigDecimal expected, String value) {
        if (value == null) return false;
        try {
            return expected.compareTo(new BigDecimal(value)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean paid(String status) {
        return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status);
    }

    private PaymentStatusVO statusOf(Order order) {
        PaymentAttempt attempt = paymentMapper.selectLatest(order.getId());
        return new PaymentStatusVO(order.getId(), order.getStatus().name(), order.getPayExpiresAt(),
                attempt == null ? null : new PaymentStatusVO.Payment(attempt.getId(),
                        attempt.getChannel(), attempt.getStatus().name(), attempt.getPaidAt()));
    }

    private Order ownedOrder(Long userId, Long orderId) {
        accountAccess.checkActive(userId);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId).eq(Order::getUserId, userId));
        if (order == null) throw new BusinessException(404, "订单不存在");
        return order;
    }

    private void requireGateway() {
        if (!gateway.configured()) throw new BusinessException(503, "支付渠道未配置");
    }

    private void requireCurrentGateway(PaymentAttempt attempt) {
        if (!currentGateway(attempt)) throw new BusinessException(409, "支付渠道配置已变化");
    }

    private boolean currentGateway(PaymentAttempt attempt) {
        return gateway.channel().equals(attempt.getChannel())
                && gateway.appId().equals(attempt.getAppId())
                && gateway.sellerId().equals(attempt.getSellerId());
    }
}
