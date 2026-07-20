package cn.jnu.zhangcs.jsqlint.server.util;

import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 日志上下文工具类。
 * <p>
 * 统一封装从 Spring Security 和当前 HTTP 请求中读取日志所需字段的逻辑。
 *
 * @author zhangcs
 */
public final class LogUtil {

    private static final String UNKNOWN = "unknown";

    private static final String[] CLIENT_IP_HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private LogUtil() {
    }

    /**
     * 获取当前登录用户ID
     *
     * @return 用户ID，未登录返回null
     */
    public static Long getCurrentUserId() {
        LoginUser loginUser = getCurrentLoginUser();
        return loginUser == null ? null : loginUser.getUserId();
    }

    /**
     * 获取当前登录用户名
     *
     * @return 用户名，未登录返回null
     */
    public static String getCurrentUsername() {
        LoginUser loginUser = getCurrentLoginUser();
        return loginUser == null ? null : loginUser.getUsername();
    }

    /**
     * 获取当前请求的IP地址
     *
     * @return IP地址，获取失败返回null
     */
    public static String getClientIpAddress() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return getIpAddress(attributes.getRequest());
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 获取当前登录用户。
     */
    private static LoginUser getCurrentLoginUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
                return loginUser;
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 从请求中获取IP地址
     *
     * @param request HTTP请求
     * @return IP地址
     */
    private static String getIpAddress(HttpServletRequest request) {
        String ip = null;
        for (String header : CLIENT_IP_HEADERS) {
            ip = request.getHeader(header);
            if (hasValidIp(ip)) {
                break;
            }
        }
        if (!hasValidIp(ip)) {
            ip = request.getRemoteAddr();
        }

        return firstForwardedIp(ip);
    }

    /**
     * 多级代理会在 X-Forwarded-For 中写入多个 IP，业务侧只使用最靠近客户端的第一个值。
     */
    private static String firstForwardedIp(String ip) {
        if (ip != null && ip.contains(",")) {
            return ip.split(",")[0].trim();
        }
        return ip;
    }

    private static boolean hasValidIp(String ip) {
        return ip != null && !ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip);
    }
}
