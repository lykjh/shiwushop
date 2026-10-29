package com.shiwushop.order;

import com.shiwushop.commerce.controller.order.OrderController;
import com.shiwushop.catalog.controller.shop.MerchantShopController;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.commerce.service.OrderService;
import com.shiwushop.catalog.service.ShopService;
import com.shiwushop.commerce.vo.order.OrderSliceVO;
import com.shiwushop.commerce.vo.order.OrderSummaryVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderListControllerSliceTests {
    private final OrderService orders = mock(OrderService.class);
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void buyerEndpointExposesCursorResponseAndRejectsPageParameter() throws Exception {
        session.setAttribute("userId", 7L);
        OrderSummaryVO row = new OrderSummaryVO();
        row.setId(10L);
        when(orders.list(7L, null, 1)).thenReturn(new OrderSliceVO(List.of(row), true, 10L));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderController(orders))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/orders?size=1").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.nextCursor").value(10))
                .andExpect(jsonPath("$.data.total").doesNotExist());
        mvc.perform(get("/orders?page=2").session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void merchantEndpointForwardsShopAndCursor() throws Exception {
        session.setAttribute("userId", 7L);
        when(orders.listMerchant(7L, 9L, 10L, 5))
                .thenReturn(new OrderSliceVO(List.of(), false, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MerchantShopController(
                mock(ShopService.class), orders)).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/merchant/shops/9/orders?afterId=10&size=5").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }
}
