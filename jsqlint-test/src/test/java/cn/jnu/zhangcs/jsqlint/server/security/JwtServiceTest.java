package cn.jnu.zhangcs.jsqlint.server.security;

import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 单元测试。
 *
 * @author zhangcs
 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setExpiration(3600_000L);
        jwtService = new JwtService(config);
    }

    @Test
    void generateToken_valid_returnsThreeSegmentJwt() {
        String token = jwtService.generateToken("zhangcs", 100L, "ROLE_ADMIN");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void parseToken_valid_returnsUsernameRoleAndUserId() {
        String token = jwtService.generateToken("zhangcs", 100L, "ROLE_USER");

        assertThat(jwtService.getUsernameFromToken(token)).isEqualTo("zhangcs");
        assertThat(jwtService.getRoleFromToken(token)).isEqualTo("ROLE_USER");
        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(100L);
    }

    @Test
    void validateToken_valid_returnsTrue() {
        String token = jwtService.generateToken("zhangcs", 1L, "ROLE_USER");
        assertThat(jwtService.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_tampered_returnsFalse() {
        String token = jwtService.generateToken("zhangcs", 1L, "ROLE_USER");
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThat(jwtService.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_malformed_returnsFalse() {
        assertThat(jwtService.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    void validateToken_empty_throwsIllegalArgumentException() {
        // jjwt 对空串抛 IllegalArgumentException（非 JwtException），不在此覆盖范围内
        assertThatThrownBy(() -> jwtService.validateToken(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateToken_signedWithDifferentSecret_returnsFalse() {
        JwtConfig otherConfig = new JwtConfig();
        otherConfig.setSecret("fedcba9876543210fedcba9876543210");
        otherConfig.setExpiration(3600_000L);
        String token = new JwtService(otherConfig).generateToken("zhangcs", 1L, "ROLE_USER");

        assertThat(jwtService.validateToken(token)).isFalse();
    }

    @Test
    void getClaimsFromToken_containsUsernameRoleAndTimestamps() {
        String token = jwtService.generateToken("zhangcs", 100L, "ROLE_ADMIN");
        Map<String, Object> claims = jwtService.getClaimsFromToken(token);

        assertThat(claims.get("username")).isEqualTo("zhangcs");
        assertThat(claims.get("role")).isEqualTo("ROLE_ADMIN");
        // getClaimsFromToken 使用字面键 issuedAt/expiration（非标准 iat/exp 键名）
        assertThat(claims.get("issuedAt")).isInstanceOf(java.util.Date.class);
        assertThat(claims.get("expiration")).isInstanceOf(java.util.Date.class);
    }

    @Test
    void validateToken_expired_returnsFalse() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setExpiration(0L);
        // 过期时间为当前时刻，构造令牌后立即校验必然过期
        String token = new JwtService(config).generateToken("zhangcs", 1L, "ROLE_USER");

        assertThat(jwtService.validateToken(token)).isFalse();
    }
}
