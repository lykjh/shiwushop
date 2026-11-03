package com.shiwushop.slice.order;
import com.shiwushop.support.ControllerSliceSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.shiwushop.trade.controller.order.OrderController;
import com.shiwushop.trade.controller.order.MerchantOrderController;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.trade.service.OrderService;
import com.shiwushop.trade.vo.order.OrderSliceVO;
import com.shiwushop.trade.vo.order.OrderSummaryVO;
import com.shiwushop.trade.vo.order.OrderDetailVO;
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

@WebMvcTest({OrderController.class, MerchantOrderController.class})
@ContextConfiguration(classes={OrderController.class, MerchantOrderController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class OrderListControllerSliceTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private OrderService orders;
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void buyerEndpointExposesCursorResponseAndRejectsPageParameter() throws Exception {
        session.setAttribute("userId", 7L);
        OrderSummaryVO row = new OrderSummaryVO();
        row.setId(10L);
        when(orders.list(7L, null, 1)).thenReturn(new OrderSliceVO(List.of(row), true, 10L));


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


        mvc.perform(get("/merchant/shops/9/orders?afterId=10&size=5").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void merchantDetailKeepsTheExistingShopScopedRoute() throws Exception {
        session.setAttribute("userId", 7L);
        when(orders.detailMerchant(7L, 9L, 11L))
                .thenReturn(new OrderDetailVO(11L, 9L, "示例店铺", "UNPAID", null, false, null, List.of()));


        mvc.perform(get("/merchant/shops/9/orders/11").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.shopName").value("示例店铺"))
                .andExpect(jsonPath("$.data.cancelPending").value(false));
    }
}
