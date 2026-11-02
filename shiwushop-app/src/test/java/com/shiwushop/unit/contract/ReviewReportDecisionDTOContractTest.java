package com.shiwushop.unit.contract;

import com.shiwushop.product.dto.review.ReviewReportDecisionDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** 验证入口格式、集合元素及长度边界，不启动 Spring 或数据库。 */
class ReviewReportDecisionDTOContractTest {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final String VALID_JSON = "{\"decision\":\"HIDE\",\"reason\":\"已核实包含违规内容\"}";

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法请求", VALID_JSON, true),
                Arguments.of("decision 非法边界 1", body("decision", null), false),
                Arguments.of("decision 非法边界 2", body("decision", "ALLOW"), false),
                Arguments.of("reason 非法边界 3", body("reason", null), false),
                Arguments.of("reason 非法边界 4", body("reason", "  "), false),
                Arguments.of("reason 非法边界 5", body("reason", "评".repeat(501)), false),
                Arguments.of("decision 合法边界 1", body("decision", "KEEP"), true));
    }

    private static String body(String field, Object value) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(VALID_JSON);
        body.set(field, JSON.valueToTree(value));
        return body.toString();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesRequest(String name, String body, boolean valid) {
        DtoContract.verify(ReviewReportDecisionDTO.class, body, valid);
    }
}
