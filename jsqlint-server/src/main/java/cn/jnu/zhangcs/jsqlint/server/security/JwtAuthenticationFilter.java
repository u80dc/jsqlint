package cn.jnu.zhangcs.jsqlint.server.security;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @Nonnull HttpServletResponse response, @Nonnull FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        log.debug("=== JwtAuthenticationFilter ===");
        log.debug("Request URI: {}", request.getRequestURI());
        log.debug("Auth Header: {}", authHeader);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            log.debug("Token: {}", token.substring(0, Math.min(20, token.length())) + "...");

            if (jwtService.validateToken(token)) {
                String username = jwtService.getUsernameFromToken(token);
                String role = jwtService.getRoleFromToken(token);
                Long userId = jwtService.getUserIdFromToken(token);

                log.debug("Token valid. Username: {}, Role: {}, UserId: {}", username, role, userId);

                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        new LoginUser(username, userId), null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute("userId", userId);
            } else {
                log.debug("Token validation failed");
            }
        } else {
            log.debug("No valid auth header");
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/login") || path.equals("/api/auth/register");
    }

}