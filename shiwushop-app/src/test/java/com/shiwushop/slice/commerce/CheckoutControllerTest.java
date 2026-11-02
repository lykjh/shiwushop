package com.shiwushop.slice.commerce;

import com.shiwushop.trade.controller.cart.CheckoutController;
import com.shiwushop.trade.service.CheckoutService;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CheckoutController.class)
@ContextConfiguration(classes={CheckoutController.class,GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class CheckoutControllerTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @MockitoBean CheckoutService service;
    Map<String,Object> body() { return new HashMap<>(Map.of("cartItemIds",List.of(1),"addressId",1,"confirmedTotal","19.90","idempotencyKey","checkout-zhang")); }
    MockHttpSession session() { var s=new MockHttpSession(); s.setAttribute("userId",42L); return s; }
    static Stream<Map.Entry<String,Object>> invalidFields() {
        return Stream.of(Map.entry("cartItemIds",List.of()),Map.entry("cartItemIds",LongStream.rangeClosed(1,101).boxed().toList()),
                Map.entry("cartItemIds",List.of(-1)),Map.entry("addressId",0),Map.entry("confirmedTotal","0"),Map.entry("confirmedTotal","-1"),
                Map.entry("idempotencyKey",""),Map.entry("idempotencyKey","a".repeat(65)));
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsInvalidRequestBeforeCheckout(Map.Entry<String,Object> field) throws Exception {
        var body=body(); body.put(field.getKey(),field.getValue());
        mvc.perform(post("/checkouts").session(session()).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    @Test void oneHundredItemsReachCheckoutService() throws Exception {
        var body=body(); body.put("cartItemIds",LongStream.rangeClosed(1,100).boxed().toList());
        mvc.perform(post("/checkouts").session(session()).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()); verify(service).checkoutSelected(eq(42L),any());
    }
    @Test void anonymousRequestCannotCheckout() throws Exception {
        mvc.perform(post("/checkouts").with(csrf()).contentType("application/json").content(json.writeValueAsString(body())))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
}
