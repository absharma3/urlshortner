package com.urlshortner.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.urlshortner.web.RateLimitingInterceptor;

/**
 * Registers {@link RateLimitingInterceptor} for {@code /**} while excluding paths that must
 * never be rate-limited — actuator (operator access, must always work), {@code /healthz}
 * (liveness probe), Swagger UI, and the servlet error dispatcher (double-counting an already-
 * failed request against the caller's quota is unfriendly).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitingInterceptor rateLimitingInterceptor;

    public WebMvcConfig(RateLimitingInterceptor rateLimitingInterceptor) {
        this.rateLimitingInterceptor = rateLimitingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitingInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/actuator/**",
                        "/healthz",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/error");
    }
}
