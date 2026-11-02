package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.NotNull;

public record ReturnReceiptDTO(@NotNull Boolean restockable) {}
