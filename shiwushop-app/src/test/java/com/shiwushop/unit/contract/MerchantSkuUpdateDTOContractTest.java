package com.shiwushop.unit.contract;

import com.shiwushop.product.dto.sku.MerchantSkuUpdateDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** MerchantSkuUpdateDTO 的必填、格式及临界值；不启动容器。 */
class MerchantSkuUpdateDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"price\":\"19.90\",\"stock\":5}", true),
                Arguments.of("price 合法边界 1", "{\"price\":\"0.01\",\"stock\":5}", true),
                Arguments.of("price 合法边界 2", "{\"price\":\"99999999.99\",\"stock\":5}", true),
                Arguments.of("stock 合法边界 3", "{\"price\":\"19.90\",\"stock\":0}", true),
                Arguments.of("price 非法边界 1", "{\"price\":null,\"stock\":5}", false),
                Arguments.of("price 非法边界 2", "{\"price\":\"0\",\"stock\":5}", false),
                Arguments.of("price 非法边界 3", "{\"price\":\"-0.01\",\"stock\":5}", false),
                Arguments.of("price 非法边界 4", "{\"price\":\"0.001\",\"stock\":5}", false),
                Arguments.of("price 非法边界 5", "{\"price\":\"100000000\",\"stock\":5}", false),
                Arguments.of("stock 非法边界 6", "{\"price\":\"19.90\",\"stock\":null}", false),
                Arguments.of("stock 非法边界 7", "{\"price\":\"19.90\",\"stock\":-1}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(MerchantSkuUpdateDTO.class,body,valid);
    }
}
