package com.budgetintel.security;

import com.budgetintel.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects calls to {@link AdminOnly} endpoints unless the X-Admin-Api-Key header equals ADMIN_API_KEY.
 * Every other endpoint (and CORS preflight requests, whose handler is not a controller method) is untouched.
 * With no key configured, administrative endpoints are locked rather than open. The key is never logged.
 */
public class AdminKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Api-Key";
    static final int MIN_KEY_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(AdminKeyInterceptor.class);

    private final byte[] expectedDigest;   // null = no key configured

    public AdminKeyInterceptor(String adminKey) {
        if (adminKey == null || adminKey.isBlank()) {
            log.warn("ADMIN_API_KEY is not set; administrative endpoints will reject every request");
            this.expectedDigest = null;
        } else if (adminKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("ADMIN_API_KEY must be at least " + MIN_KEY_LENGTH
                    + " characters (e.g. generate one with: openssl rand -hex 32)");
        } else {
            this.expectedDigest = sha256(adminKey);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(AdminOnly.class)
                && !authorized(request.getHeader(HEADER))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A valid administrator key is required for this action", List.of());
        }
        return true;
    }

    /** Compares fixed-length digests in constant time, so neither the content nor the length of the key leaks. */
    private boolean authorized(String given) {
        return expectedDigest != null && given != null && MessageDigest.isEqual(sha256(given), expectedDigest);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is required on every Java platform
        }
    }
}
