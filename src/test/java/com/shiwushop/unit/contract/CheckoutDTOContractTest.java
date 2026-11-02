package com.shiwushop.unit.contract;

import com.shiwushop.trade.dto.cart.CheckoutDTO;
import com.shiwushop.support.DtoContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

/** CheckoutDTO 的必填、格式及临界值；不启动容器。 */
class CheckoutDTOContractTest {
    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("合法默认值", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", true),
                Arguments.of("cartItemIds 合法边界 1", "{\"cartItemIds\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", true),
                Arguments.of("idempotencyKey 合法边界 2", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", true),
                Arguments.of("cartItemIds 非法边界 1", "{\"cartItemIds\":null,\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("cartItemIds 非法边界 2", "{\"cartItemIds\":[],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("cartItemIds 非法边界 3", "{\"cartItemIds\":[null],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("cartItemIds 非法边界 4", "{\"cartItemIds\":[0],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("cartItemIds 非法边界 5", "{\"cartItemIds\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100,101],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("addressId 非法边界 6", "{\"cartItemIds\":[1],\"addressId\":null,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("addressId 非法边界 7", "{\"cartItemIds\":[1],\"addressId\":0,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("confirmedTotal 非法边界 8", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":null,\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("confirmedTotal 非法边界 9", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"0\",\"idempotencyKey\":\"checkout-zhang\"}", false),
                Arguments.of("idempotencyKey 非法边界 10", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":null}", false),
                Arguments.of("idempotencyKey 非法边界 11", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\" \"}", false),
                Arguments.of("idempotencyKey 非法边界 12", "{\"cartItemIds\":[1],\"addressId\":1,\"confirmedTotal\":\"19.90\",\"idempotencyKey\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", false));
    }
    @ParameterizedTest(name="{0}") @MethodSource("boundaries")
    void verifiesBoundary(String name,String body,boolean valid) {
        DtoContract.verify(CheckoutDTO.class,body,valid);
    }
}
