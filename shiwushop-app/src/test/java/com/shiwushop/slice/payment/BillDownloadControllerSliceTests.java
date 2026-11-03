package com.shiwushop.slice.payment;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.payment.controller.AdminPaymentBillController;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.payment.service.impl.PaymentBillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@WebMvcTest(AdminPaymentBillController.class)
@ContextConfiguration(classes={AdminPaymentBillController.class,GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class BillDownloadControllerSliceTests {
 @Autowired MockMvc mvc;
 @MockitoBean PaymentBillService bills;
 @MockitoBean AdminAuth auth;
 @Test void adminCanRequestDailyBill() throws Exception {
  var session=new MockHttpSession(); session.setAttribute("userId",7L);
  when(auth.requireAdmin(any())).thenReturn(7L);
  mvc.perform(post("/admin/payment-bills").session(session).with(csrf())
          .contentType(MediaType.APPLICATION_JSON).content("{\"billDate\":\"2026-10-01\"}"))
          .andExpect(status().isOk());
 }
}
