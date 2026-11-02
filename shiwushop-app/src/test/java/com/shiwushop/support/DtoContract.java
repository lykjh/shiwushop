package com.shiwushop.support;

import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 无 Spring 的 DTO 边界校验；期望来自各测试类的明确输入。 */
public final class DtoContract {
    private DtoContract() {}
    public static <T> void verify(Class<T> type,String body,boolean valid) {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var errors=factory.getValidator().validate(new ObjectMapper().readValue(body,type));
            if(valid)assertTrue(errors.isEmpty(),()->"合法边界被拒绝: "+errors);
            else assertFalse(errors.isEmpty(),"非法边界未被拒绝");
        }
    }
}
