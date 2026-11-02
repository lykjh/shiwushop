package com.shiwushop.unit.contract;

import com.shiwushop.aftersale.dto.AfterSaleCreateDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** 验证入口格式、集合元素及长度边界，不启动 Spring 或数据库。 */
class AfterSaleCreateDTOContractTest {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final String VALID_JSON = "{\"type\":\"RETURN_REFUND\",\"reason\":\"商品存在质量问题\",\"items\":[{\"orderItemId\":1,\"quantity\":1}]}";

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法请求", VALID_JSON, true),
                Arguments.of("type 非法边界 1", body("type", null), false),
                Arguments.of("type 非法边界 2", body("type", "UNKNOWN"), false),
                Arguments.of("reason 非法边界 3", body("reason", " "), false),
                Arguments.of("reason 非法边界 4", body("reason", "评".repeat(501)), false),
                Arguments.of("items 非法边界 5", body("items", null), false),
                Arguments.of("items 非法边界 6", body("items", java.util.List.of()), false),
                Arguments.of("items 非法边界 7", body("items", java.util.Collections.singletonList(null)), false),
                Arguments.of("items 非法边界 8", body("items", java.util.List.of(JSON.readTree("{\"orderItemId\":null,\"quantity\":1}"))), false),
                Arguments.of("items 非法边界 9", body("items", java.util.List.of(JSON.readTree("{\"orderItemId\":0,\"quantity\":1}"))), false),
                Arguments.of("items 非法边界 10", body("items", java.util.List.of(JSON.readTree("{\"orderItemId\":1,\"quantity\":null}"))), false),
                Arguments.of("items 非法边界 11", body("items", java.util.List.of(JSON.readTree("{\"orderItemId\":1,\"quantity\":0}"))), false),
                Arguments.of("items 非法边界 12", body("items", java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> java.util.Map.of("orderItemId", i, "quantity", 1)).toList()), false),
                Arguments.of("items 合法边界 1", body("items", java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(i -> java.util.Map.of("orderItemId", i, "quantity", 1)).toList()), true),
                Arguments.of("type 合法边界 2", body("type", "REFUND_ONLY"), true));
    }

    private static String body(String field, Object value) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(VALID_JSON);
        body.set(field, JSON.valueToTree(value));
        return body.toString();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesRequest(String name, String body, boolean valid) {
        DtoContract.verify(AfterSaleCreateDTO.class, body, valid);
    }
}
