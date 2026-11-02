package com.shiwushop.unit.contract;

import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** SmsPhoneDTO 的必填、格式及临界值；不启动容器。 */
class SmsPhoneDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"phone\":\"13812345678\"}", true),
                Arguments.of("phone 合法边界 1", "{\"phone\":\"19912345678\"}", true),
                Arguments.of("phone 非法边界 1", "{\"phone\":null}", false),
                Arguments.of("phone 非法边界 2", "{\"phone\":\"\"}", false),
                Arguments.of("phone 非法边界 3", "{\"phone\":\"12812345678\"}", false),
                Arguments.of("phone 非法边界 4", "{\"phone\":\"1381234567\"}", false),
                Arguments.of("phone 非法边界 5", "{\"phone\":\"138123456789\"}", false),
                Arguments.of("phone 非法边界 6", "{\"phone\":\"1381234abcd\"}", false),
                Arguments.of("phone 非法边界 7", "{\"phone\":\" 13812345678\"}", false),
                Arguments.of("phone 非法边界 8", "{\"phone\":\"１３８１２３４５６７８\"}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(SmsPhoneDTO.class,body,valid);
    }
}
