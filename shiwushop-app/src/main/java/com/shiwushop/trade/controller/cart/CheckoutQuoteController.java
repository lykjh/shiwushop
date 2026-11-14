package com.shiwushop.trade.controller.cart;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.marketing.vo.QuoteVO;
import com.shiwushop.trade.dto.cart.QuoteRequest;
import com.shiwushop.trade.service.CheckoutQuoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/checkouts/quote")
public class CheckoutQuoteController {
    private final CheckoutQuoteService quotes;

    @PostMapping
    public Result<QuoteVO> quote(@Valid @RequestBody QuoteRequest body, HttpServletRequest request) {
        return Result.success(quotes.quote(SessionUser.requireId(request), body));
    }
}
