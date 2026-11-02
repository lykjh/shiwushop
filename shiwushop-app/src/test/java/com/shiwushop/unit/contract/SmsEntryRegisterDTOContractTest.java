package com.shiwushop.unit.contract;

import com.shiwushop.user.dto.user.SmsEntryRegisterDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** SmsEntryRegisterDTO 的必填、格式及临界值；不启动容器。 */
class SmsEntryRegisterDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"Shop123!\"}", true),
                Arguments.of("registrationTicket 合法边界 1", "{\"phone\":\"13812345678\",\"registrationTicket\":\"tttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"Shop123!\"}", true),
                Arguments.of("password 合法边界 2", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", true),
                Arguments.of("phone 非法边界 1", "{\"phone\":\"12812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"Shop123!\"}", false),
                Arguments.of("registrationTicket 非法边界 2", "{\"phone\":\"13812345678\",\"registrationTicket\":null,\"password\":\"Shop123!\"}", false),
                Arguments.of("registrationTicket 非法边界 3", "{\"phone\":\"13812345678\",\"registrationTicket\":\"\",\"password\":\"Shop123!\"}", false),
                Arguments.of("registrationTicket 非法边界 4", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"Shop123!\"}", false),
                Arguments.of("password 非法边界 5", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":null}", false),
                Arguments.of("password 非法边界 6", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"aaaaaaa\"}", false),
                Arguments.of("password 非法边界 7", "{\"phone\":\"13812345678\",\"registrationTicket\":\"ttttttttttttttttttttttttttttttttttttttttttt\",\"password\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", false));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"asdfg密123456", "Shop😀123!", " Shop123!", "Shop123! "})
    void rejectsIllegalPasswordCharacters(String password) {
        var body = new tools.jackson.databind.ObjectMapper().createObjectNode();
        body.put("phone", "13812345678");
        body.put("registrationTicket", "t".repeat(43));
        body.put("password", password);
        DtoContract.verify(SmsEntryRegisterDTO.class, body.toString(), false);
    }

    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(SmsEntryRegisterDTO.class,body,valid);
    }
}
