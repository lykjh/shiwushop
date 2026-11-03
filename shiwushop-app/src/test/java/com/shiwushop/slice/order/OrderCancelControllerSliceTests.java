package com.shiwushop.slice.order;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.trade.controller.order.OrderCancellationController;
import com.shiwushop.trade.service.OrderPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderCancellationController.class)
@ContextConfiguration(classes = {OrderCancellationController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class OrderCancelControllerSliceTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private OrderPaymentService payments;

    @Test
    void buyerCanRequestCancellationWithCsrf() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        when(payments.cancelOrder(7L, 10L)).thenReturn("CANCELLED");
        mvc.perform(post("/orders/10/cancel").session(session).with(csrf()))
                .andExpect(status().isOk());
    }
    @Test
    void pendingCancellationReturnsAccepted() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        when(payments.cancelOrder(7L, 10L)).thenReturn("CANCEL_PENDING");
        mvc.perform(post("/orders/10/cancel").session(session).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("CANCEL_PENDING"));
    }

    @Test
    void invalidIdsAreRejectedAtEntry() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        for (String id : new String[] {"0", "-1", "abc", "9999999999999999999999"}) {
            mvc.perform(post("/orders/" + id + "/cancel").session(session).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(payments);
    }

    @Test
    void authenticationAndCsrfAreRequired() throws Exception {
        mvc.perform(post("/orders/10/cancel").with(csrf())).andExpect(status().isUnauthorized());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        mvc.perform(post("/orders/10/cancel").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }
}
