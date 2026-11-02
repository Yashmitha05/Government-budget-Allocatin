package com.budgetintel.client;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param apiKey     shared secret sent to the ML service as X-ML-Api-Key (blank = not sent)
 * @param gcpIdToken attach a Google-signed ID token for a Cloud Run service that requires authentication
 */
@Validated
@ConfigurationProperties(prefix = "app.ml")
public record MlServiceProperties(@NotBlank String baseUrl, int connectTimeoutMs, int readTimeoutMs, String apiKey,
                                  boolean gcpIdToken) {
}
