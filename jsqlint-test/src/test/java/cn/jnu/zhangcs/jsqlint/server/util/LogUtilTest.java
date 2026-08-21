package cn.jnu.zhangcs.jsqlint.server.util;

import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LogUtil} 单元测试。
 *
 * @author zhangcs
 */
class LogUtilTest {

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void login(LoginUser loginUser) {
        Authentication auth = new UsernamePasswordAuthenticationToken(loginUser, null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private void bindRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void getCurrentUserIdAndUsername_loggedIn_returnsValues() {
        login(new LoginUser("zhangcs", 100L));
        assertThat(LogUtil.getCurrentUserId()).isEqualTo(100L);
        assertThat(LogUtil.getCurrentUsername()).isEqualTo("zhangcs");
    }

    @Test
    void getCurrentUserIdAndUsername_notLoggedIn_returnsNull() {
        assertThat(LogUtil.getCurrentUserId()).isNull();
        assertThat(LogUtil.getCurrentUsername()).isNull();
    }

    @Test
    void getCurrentUserIdAndUsername_nonLoginUserPrincipal_returnsNull() {
        login(null);
        Authentication auth = new UsernamePasswordAuthenticationToken("anonymous-string", null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertThat(LogUtil.getCurrentUserId()).isNull();
        assertThat(LogUtil.getCurrentUsername()).isNull();
    }

    @Test
    void getClientIpAddress_noRequestContext_returnsNull() {
        assertThat(LogUtil.getClientIpAddress()).isNull();
    }

    @Test
    void getClientIpAddress_noProxyHeaders_returnsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("192.168.1.10");
    }

    @Test
    void getClientIpAddress_xForwardedFor_takesPrecedence() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5");
        request.setRemoteAddr("10.0.0.1");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void getClientIpAddress_multiLevelProxy_takesFirstIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 70.41.3.18, 150.172.238.178");
        request.setRemoteAddr("10.0.0.1");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void getClientIpAddress_xForwardedForUnknown_usesNextHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Real-IP", "198.51.100.7");
        request.setRemoteAddr("10.0.0.1");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("198.51.100.7");
    }

    @Test
    void getClientIpAddress_allHeadersInvalid_returnsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "");
        request.addHeader("X-Real-IP", "unknown");
        request.addHeader("Proxy-Client-IP", "UNKNOWN");
        request.setRemoteAddr("172.16.0.9");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("172.16.0.9");
    }

    @Test
    void getClientIpAddress_wlProxyClientIpHeader_recognized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("WL-Proxy-Client-IP", "198.51.100.9");
        request.setRemoteAddr("10.0.0.1");
        bindRequest(request);
        assertThat(LogUtil.getClientIpAddress()).isEqualTo("198.51.100.9");
    }
}
