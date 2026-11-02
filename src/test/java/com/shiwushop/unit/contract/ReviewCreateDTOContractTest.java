package com.shiwushop.unit.contract;

import com.shiwushop.product.dto.review.ReviewCreateDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** 验证入口格式、集合元素及长度边界，不启动 Spring 或数据库。 */
class ReviewCreateDTOContractTest {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final String VALID_JSON = "{\"rating\":5,\"content\":\"商品包装完好，使用正常\",\"imageMediaIds\":[]}";

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法请求", VALID_JSON, true),
                Arguments.of("rating 非法边界 1", body("rating", null), false),
                Arguments.of("rating 非法边界 2", body("rating", 0), false),
                Arguments.of("rating 非法边界 3", body("rating", 6), false),
                Arguments.of("content 非法边界 4", body("content", null), false),
                Arguments.of("content 非法边界 5", body("content", "   "), false),
                Arguments.of("content 非法边界 6", body("content", "评".repeat(2001)), false),
                Arguments.of("imageMediaIds 非法边界 7", body("imageMediaIds", java.util.Collections.singletonList(null)), false),
                Arguments.of("imageMediaIds 非法边界 8", body("imageMediaIds", java.util.List.of(0)), false),
                Arguments.of("imageMediaIds 非法边界 9", body("imageMediaIds", java.util.List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)), false),
                Arguments.of("content 合法边界 1", body("content", "😀".repeat(2000)), true),
                Arguments.of("content 合法边界 2", body("content", "  " + "评".repeat(2000) + "  "), true),
                Arguments.of("imageMediaIds 合法边界 3", body("imageMediaIds", java.util.List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)), true));
    }

    private static String body(String field, Object value) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(VALID_JSON);
        body.set(field, JSON.valueToTree(value));
        return body.toString();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesRequest(String name, String body, boolean valid) {
        DtoContract.verify(ReviewCreateDTO.class, body, valid);
    }
}
