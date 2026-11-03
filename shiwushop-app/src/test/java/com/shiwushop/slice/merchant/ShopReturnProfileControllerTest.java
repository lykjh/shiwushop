package com.shiwushop.slice.merchant;

import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.merchant.controller.shop.ShopReturnProfileController;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.support.ControllerSliceSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShopReturnProfileController.class)
@ContextConfiguration(classes={ShopReturnProfileController.class, GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class ShopReturnProfileControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ShopReturnProfileService service;
    static Stream<Map.Entry<String,Object>> invalidFields() {
        return Stream.of(Map.entry("contactName", " "), Map.entry("contactName", "张".repeat(21)),
                Map.entry("contactPhone", ""), Map.entry("contactPhone", "1391234567"),
                Map.entry("consignee", " "), Map.entry("consignee", "张".repeat(21)),
                Map.entry("phone", ""), Map.entry("phone", "１２３４５６７８９０１"),
                Map.entry("province", " "), Map.entry("province", "省".repeat(65)),
                Map.entry("city", " "), Map.entry("city", "市".repeat(65)),
                Map.entry("district", " "), Map.entry("district", "区".repeat(65)),
                Map.entry("detail", " "), Map.entry("detail", "路".repeat(256)));
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsInvalidFieldsBeforeService(Map.Entry<String,Object> field) throws Exception {
        var body = new HashMap<String,Object>(Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明", "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号"));
        body.put(field.getKey(), field.getValue());
        mvc.perform(put("/merchant/shops/1/return-profile").with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"contactName", "contactPhone", "consignee", "phone", "province", "city", "district", "detail"})
    void rejectsMissingFieldsBeforeService(String field) throws Exception {
        var body = new HashMap<String,Object>(Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明", "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号"));
        body.remove(field);
        mvc.perform(put("/merchant/shops/1/return-profile").with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0", "-1", "not-a-number", "9223372036854775808"})
    void rejectsInvalidShopIdForGetAndPut(String shopId) throws Exception {
        String path = "/merchant/shops/" + shopId + "/return-profile";
        mvc.perform(get(path)).andExpect(status().isBadRequest());
        var body = Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明", "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号");
        mvc.perform(put(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
