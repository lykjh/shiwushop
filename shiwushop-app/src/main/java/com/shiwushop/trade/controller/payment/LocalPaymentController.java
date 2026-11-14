package com.shiwushop.trade.controller.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.trade.service.OrderPaymentService;
import com.shiwushop.trade.vo.payment.PaymentStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 显式模拟付款入口，启用后沿用原 Session 与 CSRF 安全链。 */
@RestController
@ConditionalOnProperty(name = "shop.payment.simulation-enabled", havingValue = "true")
@RequiredArgsConstructor
public class LocalPaymentController {
    private final OrderPaymentService payments;

    /** 无金额入参；确认金额从数据库读取，由原支付确认事务幂等入账。 */
    @PostMapping("/orders/{orderId}/payment/simulate")
    public Result<PaymentStatusVO> simulate(@PathVariable Long orderId, HttpServletRequest request) {
        return Result.success(payments.simulate(SessionUser.requireId(request), orderId));
    }
}
