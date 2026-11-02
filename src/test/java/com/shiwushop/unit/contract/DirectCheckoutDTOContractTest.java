package com.shiwushop.unit.contract;

import com.shiwushop.trade.dto.cart.DirectCheckoutDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** DirectCheckoutDTO 的必填、格式及临界值；不启动容器。 */
class DirectCheckoutDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"skuId\":1,\"quantity\":1,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", true),
                Arguments.of("quantity 合法边界 1", "{\"skuId\":1,\"quantity\":10000,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", true),
                Arguments.of("skuId 非法边界 1", "{\"skuId\":null,\"quantity\":1,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("skuId 非法边界 2", "{\"skuId\":0,\"quantity\":1,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("quantity 非法边界 3", "{\"skuId\":1,\"quantity\":0,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("quantity 非法边界 4", "{\"skuId\":1,\"quantity\":10001,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("addressId 非法边界 5", "{\"skuId\":1,\"quantity\":1,\"addressId\":0,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("confirmedTotal 非法边界 6", "{\"skuId\":1,\"quantity\":1,\"addressId\":1,\"confirmedTotal\":\"0\",\"idempotencyKey\":\"direct-zhang\"}", false),
                Arguments.of("idempotencyKey 非法边界 7", "{\"skuId\":1,\"quantity\":1,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(DirectCheckoutDTO.class,body,valid);
    }
}
