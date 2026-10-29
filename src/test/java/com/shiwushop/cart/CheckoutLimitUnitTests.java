package com.shiwushop.cart;

import com.shiwushop.commerce.dto.cart.CheckoutDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckoutLimitUnitTests {
    @Test
    void acceptsOneHundredCartItemsAndRejectsOneHundredAndOne() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            CheckoutDTO dto = new CheckoutDTO();
            dto.setAddressId(1L);
            dto.setConfirmedTotal(BigDecimal.TEN);
            dto.setIdempotencyKey(UUID.randomUUID().toString());
            dto.setCartItemIds(ids(100));
            assertEquals(0, validator.validate(dto).size());
            dto.setCartItemIds(ids(101));
            assertEquals(1, validator.validate(dto).stream()
                    .filter(violation -> violation.getPropertyPath().toString().equals("cartItemIds")).count());
        }
    }

    private List<Long> ids(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }
}
