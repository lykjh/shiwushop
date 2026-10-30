package com.shiwushop.catalog.search;

import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;

@Component
public class DiscoveryCursor {
    private final ObjectMapper json;
    private final SecretKeySpec key;

    /** 配置搜索游标签名密钥；未配置时为当前进程生成随机密钥。 */
    public DiscoveryCursor(ObjectMapper json,
                           @Value("${shop.search.cursor-secret:}") String configuredSecret) {
        this.json = json;
        byte[] secret = configuredSecret.isBlank() ? new byte[32]
                : configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (configuredSecret.isBlank()) new SecureRandom().nextBytes(secret);
        if (secret.length < 32) throw new IllegalStateException("搜索游标签名密钥至少需要32字节");
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    /** 游标携带的搜索快照、排序位置、查询指纹、访问身份和到期时间。 */
    public record State(String pit, List<Object> sort, String query, Long userId, long expiresAt) { }

    /** 把游标状态编码为 URL 安全的载荷和 HMAC 签名，防止客户端改写翻页位置。 */
    public String encode(State state) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.writeValueAsBytes(state));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成搜索游标", e);
        }
    }

    /**
     * 校验游标签名、长度、查询条件、访问身份和有效期。
     * 格式或身份不符返回 400，过期返回 410，要求客户端重新从首页开始浏览。
     */
    public State decode(String token, String query, Long userId) {
        try {
            String[] parts = token.split("\\.", -1);
            // 常量时间比较签名，随后校验查询身份和有效期。
            if (parts.length != 2 || token.length() > 8192 || !MessageDigest.isEqual(
                    Base64.getUrlDecoder().decode(parts[1]), sign(parts[0]))) {
                throw new IllegalArgumentException();
            }
            State state = json.readValue(Base64.getUrlDecoder().decode(parts[0]), State.class);
            if (!query.equals(state.query()) || !java.util.Objects.equals(userId, state.userId())
                    || state.pit() == null || state.pit().isBlank() || state.sort() == null) {
                throw new IllegalArgumentException();
            }
            if (state.expiresAt() < Instant.now().getEpochSecond()) {
                throw new BusinessException(410, "列表游标已过期，请重新加载");
            }
            return state;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "列表游标无效", e);
        }
    }

    /** 对游标载荷计算 HMAC-SHA256，供编码和常量时间签名比较使用。 */
    private byte[] sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
