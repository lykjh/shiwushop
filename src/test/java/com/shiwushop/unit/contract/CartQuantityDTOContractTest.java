package com.shiwushop.unit.contract;

import com.shiwushop.trade.dto.cart.CartQuantityDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** CartQuantityDTO 的必填、格式及临界值；不启动容器。 */
class CartQuantityDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"quantity\":1}", true),
                Arguments.of("quantity 合法边界 1", "{\"quantity\":10000}", true),
                Arguments.of("quantity 非法边界 1", "{\"quantity\":null}", false),
                Arguments.of("quantity 非法边界 2", "{\"quantity\":0}", false),
                Arguments.of("quantity 非法边界 3", "{\"quantity\":-1}", false),
                Arguments.of("quantity 非法边界 4", "{\"quantity\":10001}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(CartQuantityDTO.class,body,valid);
    }
}
