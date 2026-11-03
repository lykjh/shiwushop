package com.shiwushop.slice.inventory;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.product.controller.inventory.InventoryMovementController;
import com.shiwushop.product.service.InventoryMovementService;
import com.shiwushop.product.vo.inventory.InventoryMovementPageVO;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryMovementController.class)
@ContextConfiguration(classes = {InventoryMovementController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class InventoryMovementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean InventoryMovementService service;
    final String path = "/merchant/shops/2/skus/3/inventory-movements";

    @Test void delegatesValidFilterAndCurrentUser() throws Exception {
        when(service.list(eq(7L), eq(2L), eq(3L), any())).thenReturn(new InventoryMovementPageVO(List.of(), false, null));
        mvc.perform(get(path).sessionAttr("userId", 7L).param("reason", "ORDER_DEDUCT")
                .param("createdFrom", "2026-11-01T00:00:00").param("size", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        verify(service).list(eq(7L), eq(2L), eq(3L), argThat(q -> q.size() == 3 && q.reason().name().equals("ORDER_DEDUCT")));
    }

    @Test void rejectsInvalidFiltersBeforeService() throws Exception {
        for (String query : List.of("?size=0", "?size=101", "?afterId=0", "?reason=BAD",
                "?createdFrom=bad", "?createdFrom=2026-11-03T00:00:00&createdTo=2026-11-03T00:00:00")) {
            mvc.perform(get(path + query).sessionAttr("userId", 7L)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void requiresSession() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
