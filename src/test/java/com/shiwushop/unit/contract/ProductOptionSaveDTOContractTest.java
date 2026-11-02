package com.shiwushop.unit.contract;

import com.shiwushop.product.dto.product.ProductOptionSaveDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** ProductOptionSaveDTO 的必填、格式及临界值；不启动容器。 */
class ProductOptionSaveDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"name\":\"颜色\",\"values\":[\"白色\"]}", true),
                Arguments.of("name 合法边界 1", "{\"name\":\"色色色色色色色色色色色色色色色色色色色色色色色色色色色色色色\",\"values\":[\"白色\"]}", true),
                Arguments.of("values 合法边界 2", "{\"name\":\"颜色\",\"values\":[\"白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白\"]}", true),
                Arguments.of("name 非法边界 1", "{\"name\":null,\"values\":[\"白色\"]}", false),
                Arguments.of("name 非法边界 2", "{\"name\":\" \",\"values\":[\"白色\"]}", false),
                Arguments.of("name 非法边界 3", "{\"name\":\"色色色色色色色色色色色色色色色色色色色色色色色色色色色色色色色\",\"values\":[\"白色\"]}", false),
                Arguments.of("values 非法边界 4", "{\"name\":\"颜色\",\"values\":null}", false),
                Arguments.of("values 非法边界 5", "{\"name\":\"颜色\",\"values\":[]}", false),
                Arguments.of("values 非法边界 6", "{\"name\":\"颜色\",\"values\":[\"\"]}", false),
                Arguments.of("values 非法边界 7", "{\"name\":\"颜色\",\"values\":[\"白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白白\"]}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(ProductOptionSaveDTO.class,body,valid);
    }
}
