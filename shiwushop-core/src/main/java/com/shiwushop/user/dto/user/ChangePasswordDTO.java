package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordDTO(@NotBlank String oldPassword,
                                @NotBlank @Size(min = 8, max = 72)
                                @Pattern(regexp = "[!-~]*", message = "密码只能包含字母、数字和特定符号")
                                String newPassword) {}
