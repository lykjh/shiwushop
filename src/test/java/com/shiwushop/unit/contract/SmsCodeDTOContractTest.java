package com.shiwushop.unit.contract;

import com.shiwushop.user.dto.user.SmsCodeDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** SmsCodeDTO 的必填、格式及临界值；不启动容器。 */
class SmsCodeDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"phone\":\"13812345678\",\"code\":\"012345\"}", true),
                Arguments.of("code 合法边界 1", "{\"phone\":\"13812345678\",\"code\":\"999999\"}", true),
                Arguments.of("code 非法边界 1", "{\"phone\":\"13812345678\",\"code\":null}", false),
                Arguments.of("code 非法边界 2", "{\"phone\":\"13812345678\",\"code\":\"\"}", false),
                Arguments.of("code 非法边界 3", "{\"phone\":\"13812345678\",\"code\":\"12345\"}", false),
                Arguments.of("code 非法边界 4", "{\"phone\":\"13812345678\",\"code\":\"1234567\"}", false),
                Arguments.of("code 非法边界 5", "{\"phone\":\"13812345678\",\"code\":\"abc123\"}", false),
                Arguments.of("code 非法边界 6", "{\"phone\":\"13812345678\",\"code\":\"１２３４５６\"}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(SmsCodeDTO.class,body,valid);
    }
}
