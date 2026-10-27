package com.shiwushop.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordDTO(@NotBlank String oldPassword,
                                @NotBlank @Size(min = 8, max = 72) String newPassword) {}
