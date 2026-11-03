package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.CodePointLength;

/** 本人资料整体替换请求；空头像表示清除。 */
public record UserProfileUpdateDTO(@NotNull @CodePointLength(min = 1, max = 40) String nickname,
                                   @Positive Long avatarMediaId) {
    public UserProfileUpdateDTO {
        if (nickname != null) nickname = nickname.strip();
    }
}
