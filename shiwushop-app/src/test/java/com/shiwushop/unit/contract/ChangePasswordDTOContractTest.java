package com.shiwushop.unit.contract;

import com.shiwushop.user.dto.user.ChangePasswordDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** ChangePasswordDTO 的必填、格式及临界值；不启动容器。 */
class ChangePasswordDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\"Shop456!\"}", true),
                Arguments.of("newPassword 合法边界 1", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\"aaaaaaaa\"}", true),
                Arguments.of("newPassword 合法边界 2", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", true),
                Arguments.of("oldPassword 非法边界 1", "{\"oldPassword\":null,\"newPassword\":\"Shop456!\"}", false),
                Arguments.of("oldPassword 非法边界 2", "{\"oldPassword\":\" \",\"newPassword\":\"Shop456!\"}", false),
                Arguments.of("newPassword 非法边界 3", "{\"oldPassword\":\"Shop123!\",\"newPassword\":null}", false),
                Arguments.of("newPassword 非法边界 4", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\" \"}", false),
                Arguments.of("newPassword 非法边界 5", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\"aaaaaaa\"}", false),
                Arguments.of("newPassword 非法边界 6", "{\"oldPassword\":\"Shop123!\",\"newPassword\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", false));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"asdfg密123456", "Shop😀123!", " Shop123!", "Shop123! "})
    void rejectsIllegalPasswordCharacters(String password) {
        var body = new tools.jackson.databind.ObjectMapper().createObjectNode();
        body.put("oldPassword", "Shop123!");
        body.put("newPassword", password);
        DtoContract.verify(ChangePasswordDTO.class, body.toString(), false);
    }

    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(ChangePasswordDTO.class,body,valid);
    }
}
