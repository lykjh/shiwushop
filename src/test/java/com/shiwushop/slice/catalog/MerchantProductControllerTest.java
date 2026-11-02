package com.shiwushop.slice.catalog;

import com.shiwushop.product.controller.product.MerchantProductController;
import com.shiwushop.product.service.ProductService;
import com.shiwushop.common.GlobalExceptionHandler;
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
import java.util.*;
import java.util.stream.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MerchantProductController.class)
@ContextConfiguration(classes={MerchantProductController.class,GlobalExceptionHandler.class})
@Import(ControllerSliceSecurity.class)
class MerchantProductControllerTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @MockitoBean ProductService service;
    static Stream<Map.Entry<String,Object>> invalidProductFields() {
        return Stream.of(Map.entry("shopId",0),Map.entry("categoryId",-1),Map.entry("name",""),Map.entry("name","品".repeat(101)),
                Map.entry("imageMediaIds",LongStream.rangeClosed(1,21).boxed().toList()),Map.entry("coverMediaId",-1),
                Map.entry("coverUrl","https://example.com/old.jpg"));
    }
    @ParameterizedTest @MethodSource("invalidProductFields")
    void rejectsInvalidProductBeforeBusinessService(Map.Entry<String,Object> field) throws Exception {
        Map<String,Object> body=new HashMap<>(Map.of("shopId",1,"categoryId",1,"name","洁丽雅纯棉毛巾")); body.put(field.getKey(),field.getValue());
        mvc.perform(post("/merchant/products").with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    static Stream<Map.Entry<String,Object>> invalidSkuFields() {
        return Stream.of(Map.entry("optionValueIds",List.of()),Map.entry("optionValueIds",List.of(-1)),Map.entry("price","0"),
                Map.entry("price","-1"),Map.entry("price","0.001"),Map.entry("price","100000000.00"),Map.entry("stock",-1));
    }
    @ParameterizedTest @MethodSource("invalidSkuFields")
    void rejectsInvalidSkuBeforeBusinessService(Map.Entry<String,Object> field) throws Exception {
        Map<String,Object> body=new HashMap<>(Map.of("optionValueIds",List.of(1),"price","19.90","stock",5)); body.put(field.getKey(),field.getValue());
        mvc.perform(post("/merchant/products/1/skus").with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
}
