package com.shiwushop.unit.contract;

import com.shiwushop.support.DtoContract;
import com.shiwushop.user.dto.user.PasswordResetDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import java.util.stream.Stream;

/** 只校验重设 DTO：密码沿用可见 ASCII 的 8～72 位规则，不在服务层重复检查。 */
class PasswordResetDTOContractTest {
    static Stream<Arguments> boundaries() {
        String ticket = "t".repeat(43);
        return Stream.of(
                Arguments.of("最短密码", ticket, "Shop123!", true),
                Arguments.of("最长密码", ticket, "A".repeat(72), true),
                Arguments.of("全部可见ASCII", ticket, "!\"#$%&'()*+,-./0123456789:;<=>?@ABC[\\]^_`abc{|}~", true),
                Arguments.of("短密码", ticket, "Shop12!", false),
                Arguments.of("超长密码", ticket, "a".repeat(73), false),
                Arguments.of("千字符密码", ticket, "a".repeat(1000), false),
                Arguments.of("空密码", ticket, "", false),
                Arguments.of("缺密码", ticket, null, false),
                Arguments.of("空格密码", ticket, "        ", false),
                Arguments.of("前空格", ticket, " Shop123!", false),
                Arguments.of("后空格", ticket, "Shop123! ", false),
                Arguments.of("汉字", ticket, "Shop密123!", false),
                Arguments.of("表情", ticket, "Shop😀123!", false),
                Arguments.of("换行", ticket, "Shop123!\n", false),
                Arguments.of("缺票据", null, "Shop123!", false),
                Arguments.of("空票据", "", "Shop123!", false),
                Arguments.of("短票据", "t".repeat(42), "Shop123!", false),
                Arguments.of("长票据", "t".repeat(44), "Shop123!", false),
                Arguments.of("票据非法字符", "*".repeat(43), "Shop123!", false));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("boundaries")
    void validatesBoundary(String name, String ticket, String password, boolean valid) {
        var body = new ObjectMapper().createObjectNode();
        body.put("resetTicket", ticket); body.put("newPassword", password);
        DtoContract.verify(PasswordResetDTO.class, body.toString(), valid);
    }
}
