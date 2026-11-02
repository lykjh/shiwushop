package com.shiwushop.unit.contract;

import com.shiwushop.trade.dto.cart.CartAddDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** CartAddDTO 的必填、格式及临界值；不启动容器。 */
class CartAddDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"skuId\":1,\"quantity\":1}", true),
                Arguments.of("quantity 合法边界 1", "{\"skuId\":1,\"quantity\":10000}", true),
                Arguments.of("skuId 非法边界 1", "{\"skuId\":null,\"quantity\":1}", false),
                Arguments.of("skuId 非法边界 2", "{\"skuId\":0,\"quantity\":1}", false),
                Arguments.of("skuId 非法边界 3", "{\"skuId\":-1,\"quantity\":1}", false),
                Arguments.of("quantity 非法边界 4", "{\"skuId\":1,\"quantity\":null}", false),
                Arguments.of("quantity 非法边界 5", "{\"skuId\":1,\"quantity\":0}", false),
                Arguments.of("quantity 非法边界 6", "{\"skuId\":1,\"quantity\":-1}", false),
                Arguments.of("quantity 非法边界 7", "{\"skuId\":1,\"quantity\":10001}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(CartAddDTO.class,body,valid);
    }
}
