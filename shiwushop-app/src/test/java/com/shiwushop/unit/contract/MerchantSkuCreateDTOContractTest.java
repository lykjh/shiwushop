package com.shiwushop.unit.contract;

import com.shiwushop.product.dto.sku.MerchantSkuCreateDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** MerchantSkuCreateDTO 的必填、格式及临界值；不启动容器。 */
class MerchantSkuCreateDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"optionValueIds\":[1],\"price\":\"19.90\",\"stock\":5}", true),
                Arguments.of("price 合法边界 1", "{\"optionValueIds\":[1],\"price\":\"0.01\",\"stock\":5}", true),
                Arguments.of("price 合法边界 2", "{\"optionValueIds\":[1],\"price\":\"99999999.99\",\"stock\":5}", true),
                Arguments.of("stock 合法边界 3", "{\"optionValueIds\":[1],\"price\":\"19.90\",\"stock\":0}", true),
                Arguments.of("optionValueIds 非法边界 1", "{\"optionValueIds\":null,\"price\":\"19.90\",\"stock\":5}", false),
                Arguments.of("optionValueIds 非法边界 2", "{\"optionValueIds\":[],\"price\":\"19.90\",\"stock\":5}", false),
                Arguments.of("optionValueIds 非法边界 3", "{\"optionValueIds\":[null],\"price\":\"19.90\",\"stock\":5}", false),
                Arguments.of("optionValueIds 非法边界 4", "{\"optionValueIds\":[-1],\"price\":\"19.90\",\"stock\":5}", false),
                Arguments.of("price 非法边界 5", "{\"optionValueIds\":[1],\"price\":null,\"stock\":5}", false),
                Arguments.of("price 非法边界 6", "{\"optionValueIds\":[1],\"price\":\"0\",\"stock\":5}", false),
                Arguments.of("price 非法边界 7", "{\"optionValueIds\":[1],\"price\":\"-0.01\",\"stock\":5}", false),
                Arguments.of("price 非法边界 8", "{\"optionValueIds\":[1],\"price\":\"0.001\",\"stock\":5}", false),
                Arguments.of("price 非法边界 9", "{\"optionValueIds\":[1],\"price\":\"100000000\",\"stock\":5}", false),
                Arguments.of("stock 非法边界 10", "{\"optionValueIds\":[1],\"price\":\"19.90\",\"stock\":null}", false),
                Arguments.of("stock 非法边界 11", "{\"optionValueIds\":[1],\"price\":\"19.90\",\"stock\":-1}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(MerchantSkuCreateDTO.class,body,valid);
    }
}
