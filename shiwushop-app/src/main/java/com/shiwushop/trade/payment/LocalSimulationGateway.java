package com.shiwushop.trade.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.service.AlipayGateway;
import com.shiwushop.payment.service.RefundGateway;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** 本地模拟渠道；所有状态读取既有 MySQL 记录，不保存内存付款状态或调用资金接口。 */
@Component
@ConditionalOnProperty(name = "shop.payment.simulation-enabled", havingValue = "true")
public class LocalSimulationGateway implements AlipayGateway, RefundGateway {
    private final PaymentAttemptMapper payments;
    private final JdbcTemplate jdbc;

    public LocalSimulationGateway(PaymentAttemptMapper payments, JdbcTemplate jdbc) {
        this.payments = payments;
        this.jdbc = jdbc;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String appId() {
        return "LOCAL_SIMULATION";
    }

    @Override
    public String sellerId() {
        return "LOCAL_SIMULATION";
    }

    @Override
    public String channel() {
        return "LOCAL_SIMULATION";
    }

    @Override
    public QrCode qrCode(String outTradeNo, BigDecimal amount, LocalDateTime expiresAt) {
        localPayment(outTradeNo);
        return new QrCode("LOCAL_SIMULATION:" + outTradeNo, expiresAt);
    }

    /** 支付宝通知不能作为本地模拟确认入口。 */
    @Override
    public boolean verify(Map<String, String> form) {
        return false;
    }

    @Override
    public TradeResult query(String outTradeNo) {
        PaymentAttempt payment = localPayment(outTradeNo);
        String status = switch (payment.getStatus()) {
            case PENDING -> "WAIT_BUYER_PAY";
            case SUCCESS -> "TRADE_SUCCESS";
            case CLOSED -> "TRADE_CLOSED";
        };
        return new TradeResult(status, outTradeNo, payment.getAlipayTradeNo(), payment.getAmount());
    }

    @Override
    public boolean close(String outTradeNo) {
        return localPayment(outTradeNo).getStatus() != PaymentAttemptStatus.SUCCESS;
    }

    @Override
    public CancelResult cancel(String outTradeNo) {
        return close(outTradeNo) ? CancelResult.CLOSED : CancelResult.UNRESOLVED;
    }

    @Override
    public RefundGateway.Result refund(String outTradeNo, String tradeNo, String outRequestNo, BigDecimal amount) {
        var result = refundRecord(outTradeNo, tradeNo, outRequestNo);
        if (amount.compareTo(result.amount()) != 0)
            throw new BusinessException(409, "模拟退款金额与持久化记录不符");
        return result;
    }

    @Override
    public RefundGateway.Result query(String outTradeNo, String tradeNo, String outRequestNo) {
        return refundRecord(outTradeNo, tradeNo, outRequestNo);
    }

    /** 原退款流程已提交退款请求，查询按同一请求号恢复，重启后无需任何内存状态。 */
    private RefundGateway.Result refundRecord(String outTradeNo, String tradeNo, String outRequestNo) {
        PaymentAttempt payment = localPayment(outTradeNo);
        if (payment.getStatus() != PaymentAttemptStatus.SUCCESS || !tradeNo.equals(payment.getAlipayTradeNo()))
            throw new BusinessException(409, "模拟退款原交易不匹配");
        var amounts = jdbc.queryForList("SELECT amount FROM refund_attempts WHERE payment_attempt_id=? AND out_request_no=? "
                + "AND status IN ('PENDING','UNKNOWN','SUCCESS')", BigDecimal.class, payment.getId(), outRequestNo);
        if (amounts.isEmpty())
            throw new BusinessException(409, "模拟退款请求不存在");
        return new RefundGateway.Result(true, outTradeNo, tradeNo, outRequestNo, amounts.getFirst());
    }

    private PaymentAttempt localPayment(String outTradeNo) {
        PaymentAttempt payment = payments.selectByOutTradeNo(outTradeNo);
        if (payment == null || !channel().equals(payment.getChannel()) || !appId().equals(payment.getAppId())
                || !sellerId().equals(payment.getSellerId()))
            throw new BusinessException(409, "仅允许处理本地模拟交易");
        return payment;
    }
}
