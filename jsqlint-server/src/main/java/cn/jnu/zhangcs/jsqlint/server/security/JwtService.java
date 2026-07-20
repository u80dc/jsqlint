package cn.jnu.zhangcs.jsqlint.server.security;

import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtConfig jwtConfig;

    public String generateToken(String username, Long userId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getExpiration());

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.getSubject();
    }

    public String getRoleFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("userId", Long.class);
    }

    public Map<String, Object> getClaimsFromToken(String token) {
        Claims claims = parseClaims(token);
        return Map.of(
                "username", claims.getSubject(),
                "role", claims.get("role", String.class),
                "issuedAt", claims.getIssuedAt(),
                "expiration", claims.getExpiration()
        );
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}