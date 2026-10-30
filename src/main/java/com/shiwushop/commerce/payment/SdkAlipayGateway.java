package com.shiwushop.commerce.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeCancelModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeCancelRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeCancelResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

@Component
public class SdkAlipayGateway implements AlipayGateway, RefundGateway {
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String SANDBOX_GATEWAY_ALT = "https://openapi.alipaydev.com/gateway.do";
    private static final String PRODUCTION_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final Set<String> OFFICIAL_GATEWAYS = Set.of(
            SANDBOX_GATEWAY, SANDBOX_GATEWAY_ALT, PRODUCTION_GATEWAY);
    private final String appId;
    private final String sellerId;
    private final String publicKey;
    private final String privateKeyPath;
    private final String notifyUrl;
    private final String gatewayUrl;
    private final Clock businessClock;
    @Value("${shop.alipay.connect-timeout-ms:3000}")
    private int connectTimeoutMs = 3000;
    @Value("${shop.alipay.read-timeout-ms:5000}")
    private int readTimeoutMs = 5000;
    private volatile AlipayClient client;

    public SdkAlipayGateway(@Value("${shop.alipay.app-id}") String appId,
                            @Value("${shop.alipay.seller-id}") String sellerId,
                            @Value("${shop.alipay.public-key}") String publicKey,
                            @Value("${shop.alipay.private-key-path}") String privateKeyPath,
                            @Value("${shop.alipay.notify-url}") String notifyUrl,
                            @Value("${shop.alipay.gateway-url}") String gatewayUrl,
                            Clock businessClock) {
        this.appId = appId.strip();
        this.sellerId = sellerId.strip();
        this.publicKey = publicKey.replaceAll("-----BEGIN [^-]+-----|-----END [^-]+-----|\\s", "");
        this.privateKeyPath = privateKeyPath.strip();
        this.notifyUrl = notifyUrl.strip();
        this.gatewayUrl = gatewayUrl.strip();
        // 配置错误在启动时暴露，避免把签名支付请求发往意外地址。
        if (!OFFICIAL_GATEWAYS.contains(this.gatewayUrl)) {
            throw new IllegalArgumentException("支付宝网关地址必须使用官方沙箱或正式网关");
        }
        this.businessClock = businessClock;
    }

    /** 检查支付身份、回调地址和私钥文件是否具备发起正式渠道请求的条件。 */
    @Override
    public boolean configured() {
        if (appId.isEmpty() || sellerId.isEmpty() || publicKey.isEmpty()
                || privateKeyPath.isEmpty() || notifyUrl.isEmpty()) return false;
        try {
            URI uri = URI.create(notifyUrl);
            Path keyPath = Path.of(privateKeyPath);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !"localhost".equalsIgnoreCase(uri.getHost())
                    && keyPath.isAbsolute() && Files.isReadable(keyPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override public String appId() { return appId; }
    @Override public String sellerId() { return sellerId; }
    @Override public String channel() {
        return PRODUCTION_GATEWAY.equals(gatewayUrl) ? "ALIPAY" : "ALIPAY_SANDBOX";
    }

    /** 以订单截止时间为上限向支付宝申请二维码，并预留一分钟防止过期竞争。 */
    @Override
    public QrCode qrCode(String outTradeNo, BigDecimal amount, LocalDateTime expiresAt) {
        // 向下取整后再留一分钟余量，避免渠道二维码晚于本地订单到期。
        long validMinutes = Duration.between(LocalDateTime.now(businessClock), expiresAt).toMinutes() - 1;
        if (validMinutes < 1) throw new BusinessException(409, "订单即将过期，请重新下单");
        try {
            AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
            model.setOutTradeNo(outTradeNo);
            model.setTotalAmount(amount.toPlainString());
            model.setSubject("商城订单 " + outTradeNo);
            model.setSellerId(sellerId);
            model.setTimeExpire(expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            model.setQrCodeTimeoutExpress(validMinutes + "m");
            AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
            request.setNotifyUrl(notifyUrl);
            request.setBizModel(model);
            AlipayTradePrecreateResponse response = client().execute(request);
            if (!response.isSuccess() || response.getQrCode() == null || response.getQrCode().isBlank()) {
                throw new BusinessException(503, "支付宝二维码生成失败：" + response.getSubCode());
            }
            return new QrCode(response.getQrCode(), LocalDateTime.now(businessClock).plusMinutes(validMinutes));
        } catch (AlipayApiException e) {
            throw new BusinessException(503, "支付宝暂不可用", e);
        }
    }

    /** 使用支付宝 RSA2 公钥校验异步通知签名。 */
    @Override
    public boolean verify(Map<String, String> form) {
        if (!configured() || !"RSA2".equals(form.get("sign_type"))) return false;
        try {
            return AlipaySignature.rsaCheckV1(form, publicKey, "UTF-8", "RSA2");
        } catch (AlipayApiException e) {
            return false;
        }
    }

    /** 根据商户单号查询交易，区分渠道确认不存在与查询失败。 */
    @Override
    public TradeResult query(String outTradeNo) {
        try {
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            model.setOutTradeNo(outTradeNo);
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            request.setBizModel(model);
            AlipayTradeQueryResponse response = client().execute(request);
            if (!response.isSuccess()) {
                if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                    return new TradeResult("NOT_FOUND", outTradeNo, null, null);
                }
                throw new BusinessException(503, "支付宝查询暂不可用");
            }
            if (response.getTotalAmount() == null || response.getOutTradeNo() == null
                    || response.getTradeStatus() == null) {
                throw new BusinessException(503, "支付宝查询结果不完整");
            }
            return new TradeResult(response.getTradeStatus(), response.getOutTradeNo(),
                    response.getTradeNo(), new BigDecimal(response.getTotalAmount()));
        } catch (AlipayApiException | NumberFormatException e) {
            throw new BusinessException(503, "支付宝查询暂不可用", e);
        }
    }

    /** 请求支付宝关闭待付款交易，供订单到期处理使用。 */
    @Override
    public boolean close(String outTradeNo) {
        try {
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(outTradeNo);
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            request.setBizModel(model);
            AlipayTradeCloseResponse response = client().execute(request);
            return response.isSuccess();
        } catch (AlipayApiException e) {
            throw new BusinessException(503, "支付宝关闭交易暂不可用", e);
        }
    }

    /** 撤销可能尚未落单的渠道请求，并区分已关闭、无交易和未确定结果。 */
    @Override
    public CancelResult cancel(String outTradeNo) {
        try {
            AlipayTradeCancelModel model = new AlipayTradeCancelModel();
            model.setOutTradeNo(outTradeNo);
            AlipayTradeCancelRequest request = new AlipayTradeCancelRequest();
            request.setBizModel(model);
            AlipayTradeCancelResponse response = client().execute(request);
            // 渠道要求重试时不能把“未找到交易”当成已经安全关闭。
            if (!response.isSuccess() || !"N".equals(response.getRetryFlag())) return CancelResult.UNRESOLVED;
            if ("close".equals(response.getAction()) && outTradeNo.equals(response.getOutTradeNo())) {
                return CancelResult.CLOSED;
            }
            return response.getAction() == null ? CancelResult.NO_TRADE : CancelResult.UNRESOLVED;
        } catch (AlipayApiException e) {
            throw new BusinessException(503, "支付宝撤销结果未知，请重试核对", e);
        }
    }

    /** 使用固定退款请求号提交退款，返回渠道报告的资金变化和退款金额。 */
    @Override
    public RefundGateway.Result refund(String outTradeNo, String tradeNo, String outRequestNo, BigDecimal amount) {
        try {
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(outTradeNo);
            model.setTradeNo(tradeNo);
            model.setOutRequestNo(outRequestNo);
            model.setRefundAmount(amount.toPlainString());
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            request.setBizModel(model);
            AlipayTradeRefundResponse response = client().execute(request);
            BigDecimal refunded = response.getRefundFee() == null ? null : new BigDecimal(response.getRefundFee());
            return new RefundGateway.Result(response.isSuccess() && "Y".equals(response.getFundChange()),
                    response.getOutTradeNo(), response.getTradeNo(), null, refunded);
        } catch (AlipayApiException | NumberFormatException e) {
            throw new BusinessException(503, "支付宝退款结果未知，请查询", e);
        }
    }

    /** 按原商户单号、交易号和退款请求号查询退款最终状态。 */
    @Override
    public RefundGateway.Result query(String outTradeNo, String tradeNo, String outRequestNo) {
        try {
            AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
            model.setOutTradeNo(outTradeNo);
            model.setTradeNo(tradeNo);
            model.setOutRequestNo(outRequestNo);
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            request.setBizModel(model);
            AlipayTradeFastpayRefundQueryResponse response = client().execute(request);
            BigDecimal amount = response.getRefundAmount() == null ? null : new BigDecimal(response.getRefundAmount());
            return new RefundGateway.Result(response.isSuccess()
                    && "REFUND_SUCCESS".equals(response.getRefundStatus()),
                    response.getOutTradeNo(), response.getTradeNo(), response.getOutRequestNo(), amount);
        } catch (AlipayApiException | NumberFormatException e) {
            throw new BusinessException(503, "支付宝退款查询暂不可用", e);
        }
    }

    /** 延迟加载并复用 SDK 客户端；首次创建时读取私钥并设置连接和读取超时。 */
    private AlipayClient client() {
        if (!configured()) throw new BusinessException(503, "支付渠道未配置");
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        String privateKey = Files.readString(Path.of(privateKeyPath), StandardCharsets.UTF_8)
                                .replaceAll("-----BEGIN [^-]+-----|-----END [^-]+-----|\\s", "");
                        // A bounded channel wait keeps one stalled request from occupying a scan indefinitely.
                        client = DefaultAlipayClient.builder(gatewayUrl, appId, privateKey)
                                .format("json").charset("UTF-8").alipayPublicKey(publicKey).signType("RSA2")
                                .connectTimeout(connectTimeoutMs).readTimeout(readTimeoutMs).build();
                    } catch (IOException e) {
                        throw new BusinessException(503, "支付渠道未配置", e);
                    }
                }
            }
        }
        return client;
    }
}
