package com.shiwushop.trade.service;

import com.shiwushop.trade.dto.cart.QuoteRequest;
import com.shiwushop.marketing.vo.QuoteVO;

public interface CheckoutQuoteService {
    QuoteVO quote(Long user, QuoteRequest request);
}
