package com.shiwushop.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record PointAdjustment(
    /** 本次积分调整的最小单位数，正数增加、负数扣减；100 单位为 1 积分。 */
    @NotNull @JsonDeserialize(using = PointAdjustment.IntegerUnits.class) Long deltaPointUnits,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason,
    /** 操作凭据引用。 */
    @NotBlank @Size(max = 500) String evidenceRef,
    /** 正向积分调整必填到期时间；负向调整不填。 */
    OffsetDateTime expiresAt
) {
    /** 禁止默认数值强转把 1.5 单位截断为 1，校验仅作用于此公开请求字段。 */
    public static final class IntegerUnits extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class, "deltaPointUnits 必须为 JSON 整数");
            return parser.getLongValue();
        }
    }
}
