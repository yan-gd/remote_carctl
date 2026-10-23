package org.example.patrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Browser CORS allowlist for the Flutter web client.
 *
 * <p>The production app is served by nginx from the same public origin, but Spring only sees the
 * proxied {@code http://127.0.0.1:8080} request. A browser POST carries an {@code Origin} header,
 * so the public HTTPS origin must therefore be listed explicitly (otherwise Spring's CORS filter
 * rejects it with 403). Development lists the {@code flutter run} origin. Wildcards are rejected:
 * this API uses bearer tokens, which must never be exposed to arbitrary origins.
 */
@Configuration
public class WebCorsConfiguration {
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${patrol.web.allowed-origins:}") String allowedOrigins) {
        var source = new UrlBasedCorsConfigurationSource();
        var origins = parseOrigins(allowedOrigins);
        if (origins.isEmpty()) return source;

        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("WWW-Authenticate"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Visible for tests: validate the allowlist without starting a context. */
    static List<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) return List.of();
        var origins =
                Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (var origin : origins) {
            if ("*".equals(origin))
                throw new IllegalArgumentException(
                        "patrol.web.allowed-origins 不能使用 *，请填写明确的前端来源");
            if (!origin.matches("https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?"))
                throw new IllegalArgumentException(
                        "patrol.web.allowed-origins 中的来源格式不正确：" + origin);
        }
        return origins;
    }
}
