package com.shiwushop.unit.contract;

import com.shiwushop.aftersale.dto.ReturnReceiptDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** 验证入口格式、集合元素及长度边界，不启动 Spring 或数据库。 */
class ReturnReceiptDTOContractTest {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final String VALID_JSON = "{\"restockable\":true}";

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法请求", VALID_JSON, true),
                Arguments.of("restockable 非法边界 1", body("restockable", null), false),
                Arguments.of("restockable 合法边界 1", body("restockable", false), true));
    }

    private static String body(String field, Object value) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(VALID_JSON);
        body.set(field, JSON.valueToTree(value));
        return body.toString();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesRequest(String name, String body, boolean valid) {
        DtoContract.verify(ReturnReceiptDTO.class, body, valid);
    }
}
