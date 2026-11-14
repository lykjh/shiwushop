package com.shiwushop.user.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.auth.SessionUser.Identity;
import com.shiwushop.user.entity.status.AccountStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtLogin {
    public static final String COOKIE = "MALLAUTH";
    private static final String ASSISTANT_COOKIE_PATH = "/assistant";
    private static final String ISSUER = "shiwushop";
    private static final String AUDIENCE = "shiwushop-web";

    private final LoginSessions sessions;
    private final AccountAuthCache accounts;
    private final Clock clock;
    private final Duration lifetime;
    private final String cookiePath;
    private final boolean secure;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtLogin(LoginSessions sessions, AccountAuthCache accounts, Clock clock,
                    @Value("${shop.auth.jwt.secret}") String secret,
                    @Value("${shop.auth.jwt.lifetime}") Duration lifetime,
                    @Value("${shop.auth.jwt.cookie-path}") String cookiePath,
                    @Value("${shop.auth.jwt.cookie-secure}") boolean secure) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.clock = clock;
        this.lifetime = lifetime;
        this.cookiePath = cookiePath;
        this.secure = secure;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SHOP_AUTH_JWT_SECRET 必须是 Base64 编码的随机密钥");
        }
        if (bytes.length < 32)
            throw new IllegalArgumentException("SHOP_AUTH_JWT_SECRET 解码后至少需要 32 字节");
        if (lifetime.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalArgumentException("SHOP_AUTH_JWT_LIFETIME 至少需要 1 秒");
        if (!cookiePath.matches("/[A-Za-z0-9/_-]*"))
            throw new IllegalArgumentException("SHOP_AUTH_JWT_COOKIE_PATH 必须是绝对 URL 路径");
        key = new SecretKeySpec(bytes, "HmacSHA256");
        parser = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                .clock(() -> Date.from(clock.instant())).sig().clear().add(Jwts.SIG.HS256).and()
                .zip().clear().and().build();
    }

    public void start(Long userId, int authVersion, HttpServletRequest request, HttpServletResponse response) {
        var state = accounts.current(userId);
        if (state == null || state.status() != AccountStatus.ACTIVE || state.version() != authVersion)
            throw new BusinessException(401, "账号状态已变更，请重新登录");

        Identity identity = new Identity(userId, authVersion, UUID.randomUUID().toString());
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plusSeconds(lifetime.toSeconds());
        String jwt = Jwts.builder().issuer(ISSUER).audience().add(AUDIENCE).and()
                .subject(userId.toString()).id(identity.sessionRef()).claim("ver", authVersion)
                .issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).signWith(key, Jwts.SIG.HS256).compact();
        revokeCurrent(request);
        sessions.create(identity, expiresAt);
        writeCookie(response, jwt, lifetime);
    }

    public Identity authenticate(HttpServletRequest request) {
        Identity identity = read(request);
        if (identity == null)
            return null;
        var state = accounts.current(identity.userId());
        if (state == null || state.status() != AccountStatus.ACTIVE || state.version() != identity.authVersion())
            return null;
        return sessions.touch(identity) ? identity : null;
    }

    private Identity read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null)
            return null;
        for (Cookie cookie : cookies) {
            if (!COOKIE.equals(cookie.getName()))
                continue;
            try {
                Claims claims = parser.parseSignedClaims(cookie.getValue()).getPayload();
                long userId = Long.parseLong(claims.getSubject());
                Integer version = claims.get("ver", Integer.class);
                String sid = claims.getId();
                Date issuedAt = claims.getIssuedAt();
                Date expiry = claims.getExpiration();
                if (userId <= 0 || version == null || version < 0 || sid == null
                        || !UUID.fromString(sid).toString().equals(sid)
                        || issuedAt == null || expiry == null || !expiry.after(issuedAt)
                        || !clock.instant().isBefore(expiry.toInstant()))
                    return null;
                return new Identity(userId, version, sid);
            } catch (JwtException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public void revokeCurrent(HttpServletRequest request) {
        Identity identity = SessionUser.current(request);
        if (identity != null)
            sessions.logout(identity);
    }

    public void clearCookie(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        var cookie = ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(secure).sameSite("Lax").maxAge(maxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.path(cookiePath).build().toString());
        // /api and /assistant are sibling paths. Both cookies refer to the same revocable login.
        if (!"/".equals(cookiePath) && !ASSISTANT_COOKIE_PATH.equals(cookiePath)) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.path(ASSISTANT_COOKIE_PATH).build().toString());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
