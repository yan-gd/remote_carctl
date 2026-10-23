package org.example.patrol.security;

import org.example.patrol.domain.gateway.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain security(
            HttpSecurity http, TokenGateway tokens, UserGateway users, SecurityErrors errors)
            throws Exception {
        http.csrf()
                .disable()
                .cors()
                .and()
                .formLogin()
                .disable()
                .httpBasic()
                .disable()
                .logout()
                .disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
                .permitAll()
                .antMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/**")
                .authenticated()
                .antMatchers(HttpMethod.POST, "/api/v1/routes", "/api/v1/tasks")
                .hasRole("OPERATOR")
                .antMatchers(HttpMethod.PUT, "/api/v1/routes/**", "/api/v1/tasks/**")
                .hasRole("OPERATOR")
                .antMatchers(HttpMethod.DELETE, "/api/v1/routes/**", "/api/v1/tasks/**")
                .hasRole("OPERATOR")
                .antMatchers(HttpMethod.PATCH, "/api/v1/alarms/*/ack")
                .hasRole("MONITOR")
                // Per-command rule (MONITOR may only STOP) is enforced in RobotCommandExecutor.
                .antMatchers(HttpMethod.POST, "/api/v1/robot/control")
                .hasAnyRole("OPERATOR", "MONITOR")
                .anyRequest()
                .authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(
                        (req, res, e) -> errors.write(req, res, 401, "UNAUTHORIZED", "请先登录"))
                .accessDeniedHandler(
                        (req, res, e) -> errors.write(req, res, 403, "FORBIDDEN", "当前角色无权执行此操作"));
        http.addFilterBefore(
                new JwtAuthenticationFilter(tokens, users, errors),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
