package com.budgetintel.config;

import com.budgetintel.security.AdminKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React frontend. CORS_ALLOWED_ORIGINS takes exact origins and/or patterns, so Cloudflare Pages
 * preview deployments can be allowed with e.g. https://*.budget-intel.pages.dev.
 * Administrative endpoints (@AdminOnly) require the X-Admin-Api-Key header to match ADMIN_API_KEY.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final AdminKeyInterceptor adminKey;

    public WebConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                     @Value("${app.admin.api-key:}") String adminApiKey) {
        this.allowedOrigins = allowedOrigins.trim().split("\\s*,\\s*");
        this.adminKey = new AdminKeyInterceptor(adminApiKey);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns(allowedOrigins).allowedMethods("GET", "POST", "DELETE")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminKey).addPathPatterns("/api/**");
    }
}
