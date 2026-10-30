package com.shiwushop.commerce.controller.payment;

import com.shiwushop.commerce.vo.payment.PaymentStartVO;
import com.shiwushop.commerce.vo.payment.PaymentStatusVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.commerce.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final PaymentService paymentService;
    private static final String ALIPAY_NOTIFY_FAILURE = "failure";

    /**
     * 为本人订单发起支付；服务层创建或复用支付尝试，控制器把站内收银台路径转成完整 URL。
     */
    @PostMapping("/orders/{orderId}/payment")
    public Result<PaymentStartVO> start(@PathVariable Long orderId, HttpServletRequest request) {
        PaymentStartVO payment = paymentService.start(SessionUser.requireId(request), orderId);
        String checkoutUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(payment.payUrl()).toUriString();
        return Result.success(new PaymentStartVO(payment.paymentId(), payment.orderId(),
                payment.channel(), payment.status(), checkoutUrl));
    }

    /** 根据商户订单号输出支付宝收银台页面，并禁止浏览器缓存支付页面。 */
    @GetMapping(value = "/payments/checkout/{outTradeNo}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> checkout(@PathVariable String outTradeNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(paymentService.checkoutPage(outTradeNo));
    }

    /** 查询本人订单的本地支付状态，不主动请求支付网关。 */
    @GetMapping("/orders/{orderId}/payment")
    public Result<PaymentStatusVO> status(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(paymentService.status(SessionUser.requireId(request), orderId));
    }

    /** 主动向支付网关核对本人订单的支付结果，并返回更新后的状态。 */
    @PostMapping("/orders/{orderId}/payment/sync")
    public Result<PaymentStatusVO> sync(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(paymentService.sync(SessionUser.requireId(request), orderId));
    }

    /**
     * 接收支付宝表单回调。拒绝重复参数，再交给服务层验签并幂等落库；
     * 只有全部处理成功才返回支付宝要求的纯文本 success，失败返回 failure 供重试。
     */
    @PostMapping(value = "/payments/alipay/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notifyAlipay(@RequestParam MultiValueMap<String, String> form) {
        if (form.values().stream().anyMatch(values -> values.size() != 1)) {
            log.warn("支付宝回调包含重复参数");
            return ResponseEntity.badRequest().body(ALIPAY_NOTIFY_FAILURE);
        }
        try {
            paymentService.notifyPaid(form.toSingleValueMap());
            return ResponseEntity.ok("success");
        } catch (BusinessException e) {
            log.warn("支付宝回调校验失败：{}", e.getMessage());
            return ResponseEntity.badRequest().body(ALIPAY_NOTIFY_FAILURE);
        } catch (Exception e) {
            log.error("处理支付宝回调失败", e);
            return ResponseEntity.badRequest().body(ALIPAY_NOTIFY_FAILURE);
        }
    }

}
