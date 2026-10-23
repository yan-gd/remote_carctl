package org.example.patrol.security;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import javax.servlet.*;
import javax.servlet.http.*;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenGateway tokens;
    private final UserGateway users;
    private final SecurityErrors errors;

    public JwtAuthenticationFilter(TokenGateway tokens, UserGateway users, SecurityErrors errors) {
        this.tokens = tokens;
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null) {
            try {
                if (!header.startsWith("Bearer ") || header.length() > 8192)
                    throw new BusinessException(
                            BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "需要有效的Bearer凭证");
                long id = tokens.verify(header.substring(7));
                var user = users.userById(id);
                if (user == null)
                    throw new BusinessException(
                            BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "登录用户不存在");
                var auth =
                        new UsernamePasswordAuthenticationToken(
                                new Actor(id, user.username()),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (BusinessException e) {
                SecurityContextHolder.clearContext();
                errors.write(req, res, 401, e.getCode(), e.getMessage());
                return;
            } catch (org.springframework.dao.DataAccessException e) {
                SecurityContextHolder.clearContext();
                errors.write(req, res, 500, "DATABASE_ERROR", "数据服务暂不可用");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
