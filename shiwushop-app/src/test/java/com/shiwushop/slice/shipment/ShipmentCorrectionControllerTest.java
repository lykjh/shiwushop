package com.shiwushop.slice.shipment;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.common.auth.AdminAuth;
import com.shiwushop.common.auth.AccountSecurity;
import com.shiwushop.support.ControllerSliceSecurity;
import com.shiwushop.trade.controller.shipment.correction.ShipmentCorrectionController;
import com.shiwushop.trade.service.ShipmentCorrectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShipmentCorrectionController.class)
@ContextConfiguration(classes = {ShipmentCorrectionController.class, GlobalExceptionHandler.class})
@Import({ControllerSliceSecurity.class, AdminAuth.class})
class ShipmentCorrectionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ShipmentCorrectionService service;
    @MockitoBean AccountSecurity accountSecurity;
    @Test void rejectsInvalidRequestAndMissingCsrfBeforeService() throws Exception {
        String path = "/merchant/shops/1/orders/2/shipment/corrections";
        mvc.perform(post(path).sessionAttr("userId", 3L).with(csrf()).contentType("application/json")
                .content("{\"originalVersion\":-1,\"carrierName\":\"\",\"trackingNo\":\"x\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).sessionAttr("userId", 3L).contentType("application/json")
                .content("{\"originalVersion\":0,\"carrierName\":\"x\",\"trackingNo\":\"x\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void rejectsAnonymousAndInvalidAdminFilters() throws Exception {
        mvc.perform(get("/admin/shipment-corrections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/shipment-corrections").sessionAttr("userId", 3L).param("status", "BAD"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/shipment-corrections").sessionAttr("userId", 3L).param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
