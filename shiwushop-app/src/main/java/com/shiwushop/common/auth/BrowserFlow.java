package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Component
public class BrowserFlow {
    public static final String COOKIE = "__Host-MALLBROWSER";
    public static final String INSECURE_COOKIE = "MALLBROWSER";
    static final String REQUEST_ID = BrowserFlow.class.getName() + ".id";
    private static final String REQUEST_EXPIRY = BrowserFlow.class.getName() + ".expiry";
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final SecretKeySpec key;
    private final boolean secure;

    public BrowserFlow(Clock clock, @Value("${shop.auth.flow.secret}") String secret,
                       @Value("${shop.auth.flow.cookie-secure}") boolean secure) {
        this.clock = clock;
        this.secure = secure;
        byte[] bytes = Base64.getDecoder().decode(secret);
        if (bytes.length < 32)
            throw new IllegalArgumentException("SHOP_AUTH_FLOW_SECRET 解码后至少需要 32 字节");
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public void identify(HttpServletRequest request) {
        String value = cookie(request, cookieName(COOKIE, INSECURE_COOKIE));
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}\\.[0-9]{1,12}\\.[A-Za-z0-9_-]{43}"))
            return;
        int separator = value.lastIndexOf('.');
        if (!valid("browser", value.substring(0, separator), value.substring(separator + 1)))
            return;
        int idEnd = value.indexOf('.');
        long expiry = Long.parseLong(value.substring(idEnd + 1, separator));
        if (expiry > now()) {
            request.setAttribute(REQUEST_ID, value.substring(0, idEnd));
            request.setAttribute(REQUEST_EXPIRY, expiry);
        }
    }

    public static String currentId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ID);
    }

    public static String requireId(HttpServletRequest request) {
        String id = currentId(request);
        if (id == null)
            throw new BusinessException(403, "请刷新页面后重试");
        return id;
    }

    public String getOrCreate(HttpServletRequest request, HttpServletResponse response) {
        String id = currentId(request);
        if (id == null) {
            id = randomId();
            long expiry = now() + LIFETIME.toSeconds();
            writeBrowser(response, id, expiry);
            request.setAttribute(REQUEST_ID, id);
            request.setAttribute(REQUEST_EXPIRY, expiry);
        } else if (request.getAttribute(REQUEST_EXPIRY) instanceof Long expiry && expiry - now() <= 300) {
            long extended = expiry + LIFETIME.toSeconds();
            writeBrowser(response, id, extended);
            request.setAttribute(REQUEST_EXPIRY, extended);
        }
        return id;
    }

    public void rotate(HttpServletResponse response) {
        writeBrowser(response, randomId(), now() + LIFETIME.toSeconds());
    }

    private void writeBrowser(HttpServletResponse response, String id, long expiry) {
        String payload = id + "." + expiry;
        write(response, cookieName(COOKIE, INSECURE_COOKIE), payload + "." + sign("browser", payload),
                Duration.ofSeconds(expiry - now()));
    }

    static long expiry(HttpServletRequest request) {
        return request.getAttribute(REQUEST_EXPIRY) instanceof Long expiry ? expiry : 0L;
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    long now() {
        return clock.instant().getEpochSecond();
    }

    String cookieName(String secureName, String insecureName) {
        return secure ? secureName : insecureName;
    }

    void write(HttpServletResponse response, String name, String value, Duration lifetime) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(lifetime).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    static String cookie(HttpServletRequest request, String name) {
        String value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) {
                    if (value != null)
                        return null;
                    value = cookie.getValue();
                }
            }
        }
        return value;
    }

    String sign(String purpose, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal((purpose + "\0" + payload).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法签名浏览器凭证", e);
        }
    }

    boolean valid(String purpose, String payload, String signature) {
        return MessageDigest.isEqual(sign(purpose, payload).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
