package com.shiwushop.marketing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

/** 金额按数值、时间按数据库毫秒精度比较；数组顺序属于原请求内容。 */
@Component
@RequiredArgsConstructor
public class ActivityContentHash {
    private final ObjectMapper json;

    public byte[] hash(Object value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(normalize(json.valueToTree(value)))
                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM缺少SHA-256", impossible);
        }
    }

    public String etag(Object value) {
        return "\"" + HexFormat.of().formatHex(hash(value)) + "\"";
    }

    private Object normalize(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : node.properties()) {
                var value = entry.getValue();
                if (!value.isNull() &&
                        Set.of("startAt", "endAt", "claimEndAt", "fixedExpireAt", "expiresAt").contains(entry.getKey()))
                    sorted.put(entry.getKey(),
                            ActivityDraftRules.time(OffsetDateTime.parse(value.asString())).toString());
                else
                    sorted.put(entry.getKey(), normalize(value));
            }
            return sorted;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            for (var value : node)
                values.add(normalize(value));
            return values;
        }
        if (node.isNumber())
            return node.decimalValue().stripTrailingZeros();
        if (node.isNull())
            return null;
        if (node.isBoolean())
            return node.asBoolean();
        return node.asString();
    }
}
