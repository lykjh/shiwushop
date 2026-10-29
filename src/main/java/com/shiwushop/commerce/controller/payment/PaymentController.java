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
    @PostMapping("/orders/{orderId}/payment")
    public Result<PaymentStartVO> start(@PathVariable Long orderId, HttpServletRequest request) {
        PaymentStartVO payment = paymentService.start(SessionUser.requireId(request), orderId);
        String checkoutUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(payment.payUrl()).toUriString();
        return Result.success(new PaymentStartVO(payment.paymentId(), payment.orderId(),
                payment.channel(), payment.status(), checkoutUrl));
    }

    @GetMapping(value = "/payments/checkout/{outTradeNo}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> checkout(@PathVariable String outTradeNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(paymentService.checkoutPage(outTradeNo));
    }

    @GetMapping("/orders/{orderId}/payment")
    public Result<PaymentStatusVO> status(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(paymentService.status(SessionUser.requireId(request), orderId));
    }

    @PostMapping("/orders/{orderId}/payment/sync")
    public Result<PaymentStatusVO> sync(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(paymentService.sync(SessionUser.requireId(request), orderId));
    }

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
