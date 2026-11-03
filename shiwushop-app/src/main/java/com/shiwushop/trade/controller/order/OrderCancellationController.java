package com.shiwushop.trade.controller.order;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.trade.service.OrderPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OrderCancellationController {
    private final OrderPaymentService payments;

    /** 请求本人订单取消，未决结果由后台继续核对。 */
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<Result<CancelResult>> cancel(@PathVariable @Positive Long orderId, HttpServletRequest request) {
        String status = payments.cancelOrder(SessionUser.requireId(request), orderId);
        return ResponseEntity.status("CANCELLED".equals(status) ? 200 : 202)
                .body(Result.success(new CancelResult(orderId, status)));
    }

    public record CancelResult(Long orderId, String status) {}
}
