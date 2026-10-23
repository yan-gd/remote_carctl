package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;

import org.example.patrol.config.WebCorsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class WebCorsConfigurationTest {
    private final WebCorsConfiguration configuration = new WebCorsConfiguration();

    @Test
    void allowsOnlyConfiguredOriginsOnApiPaths() {
        CorsConfigurationSource source =
                configuration.corsConfigurationSource(
                        "https://agilex.yoyoyan.cn, http://127.0.0.1:8090");
        CorsConfiguration api =
                source.getCorsConfiguration(
                        new MockHttpServletRequest("OPTIONS", "/api/v1/robot/status"));

        assertNotNull(api);
        assertEquals("https://agilex.yoyoyan.cn", api.checkOrigin("https://agilex.yoyoyan.cn"));
        assertEquals("http://127.0.0.1:8090", api.checkOrigin("http://127.0.0.1:8090"));
        assertNull(api.checkOrigin("https://evil.example"));
        assertNotEquals(Boolean.TRUE, api.getAllowCredentials());
        assertNull(
                source.getCorsConfiguration(
                        new MockHttpServletRequest("GET", "/swagger-ui.html")));
    }

    @Test
    void blankDisablesCrossOriginAccess() {
        CorsConfigurationSource source = configuration.corsConfigurationSource(" ");
        assertNull(
                source.getCorsConfiguration(
                        new MockHttpServletRequest("OPTIONS", "/api/v1/robot/status")));
    }

    @Test
    void wildcardIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.corsConfigurationSource("*"));
    }

    @Test
    void originWithAPathIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.corsConfigurationSource("https://evil.example/path"));
    }
}
