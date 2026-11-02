package com.budgetintel.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * Google-signed ID token for calling a private Cloud Run service (service-to-service authentication).
 * The token comes from the Cloud Run metadata server, so no key file or library is needed. Tokens are valid
 * for one hour and are reused for 50 minutes.
 */
public class GcpIdTokenProvider {

    static final String METADATA_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=";
    private static final Duration REUSE = Duration.ofMinutes(50);

    private final String url;
    private final Function<String, String> fetch;
    private final Clock clock;
    private String token;
    private Instant expires = Instant.EPOCH;

    public GcpIdTokenProvider(String audience) {
        this(audience, GcpIdTokenProvider::fetchFromMetadataServer, Clock.systemUTC());
    }

    GcpIdTokenProvider(String audience, Function<String, String> fetch, Clock clock) {
        this.url = METADATA_URL + URLEncoder.encode(audience.replaceAll("/+$", ""), StandardCharsets.UTF_8);
        this.fetch = fetch;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (token == null || now.isAfter(expires)) {
            token = fetch.apply(url);
            expires = now.plus(REUSE);
        }
        return token;
    }

    private static String fetchFromMetadataServer(String url) {
        try {
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).header("Metadata-Flavor", "Google")
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body().isBlank()) {
                throw new MlServiceUnavailableException("Metadata server returned HTTP " + res.statusCode(), null);
            }
            return res.body().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MlServiceUnavailableException("Interrupted while obtaining service credentials", e);
        } catch (java.io.IOException e) {
            throw new MlServiceUnavailableException("Could not obtain service credentials for the ML service", e);
        }
    }
}
