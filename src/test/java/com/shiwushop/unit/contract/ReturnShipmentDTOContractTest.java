package com.shiwushop.unit.contract;

import com.shiwushop.aftersale.dto.ReturnShipmentDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** 验证入口格式、集合元素及长度边界，不启动 Spring 或数据库。 */
class ReturnShipmentDTOContractTest {
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final String VALID_JSON = "{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF1234567890123\"}";

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法请求", VALID_JSON, true),
                Arguments.of("carrierName 非法边界 1", body("carrierName", null), false),
                Arguments.of("carrierName 非法边界 2", body("carrierName", " "), false),
                Arguments.of("carrierName 非法边界 3", body("carrierName", "运".repeat(81)), false),
                Arguments.of("trackingNo 非法边界 4", body("trackingNo", null), false),
                Arguments.of("trackingNo 非法边界 5", body("trackingNo", "1".repeat(101)), false),
                Arguments.of("carrierName 合法边界 1", body("carrierName", "运".repeat(80)), true),
                Arguments.of("trackingNo 合法边界 2", body("trackingNo", "1".repeat(100)), true));
    }

    private static String body(String field, Object value) {
        var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(VALID_JSON);
        body.set(field, JSON.valueToTree(value));
        return body.toString();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesRequest(String name, String body, boolean valid) {
        DtoContract.verify(ReturnShipmentDTO.class, body, valid);
    }
}
