package com.shiwushop.payment.dto;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;
public record PaymentBillDownloadDTO(@NotNull @Past LocalDate billDate) {}
