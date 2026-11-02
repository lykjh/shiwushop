package com.shiwushop.unit.contract;

import com.shiwushop.user.dto.user.PhoneBindDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** PhoneBindDTO 的必填、格式及临界值；不启动容器。 */
class PhoneBindDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"currentPassword\":\"Shop123!\",\"phone\":\"13812345678\",\"code\":\"012345\"}", true),
                Arguments.of("currentPassword 非法边界 1", "{\"currentPassword\":null,\"phone\":\"13812345678\",\"code\":\"012345\"}", false),
                Arguments.of("currentPassword 非法边界 2", "{\"currentPassword\":\" \",\"phone\":\"13812345678\",\"code\":\"012345\"}", false),
                Arguments.of("phone 非法边界 3", "{\"currentPassword\":\"Shop123!\",\"phone\":\"12812345678\",\"code\":\"012345\"}", false),
                Arguments.of("phone 非法边界 4", "{\"currentPassword\":\"Shop123!\",\"phone\":null,\"code\":\"012345\"}", false),
                Arguments.of("code 非法边界 5", "{\"currentPassword\":\"Shop123!\",\"phone\":\"13812345678\",\"code\":\"12345\"}", false),
                Arguments.of("code 非法边界 6", "{\"currentPassword\":\"Shop123!\",\"phone\":\"13812345678\",\"code\":\"1234567\"}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(PhoneBindDTO.class,body,valid);
    }
}
