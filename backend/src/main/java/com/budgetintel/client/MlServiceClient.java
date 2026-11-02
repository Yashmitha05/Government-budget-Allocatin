package com.budgetintel.client;

import com.budgetintel.client.MlDtos.MlBacktest;
import com.budgetintel.client.MlDtos.MlDataset;
import com.budgetintel.client.MlDtos.MlDeviationRecord;
import com.budgetintel.client.MlDtos.MlError;
import com.budgetintel.client.MlDtos.MlEvaluate;
import com.budgetintel.client.MlDtos.MlEvaluateRecord;
import com.budgetintel.client.MlDtos.MlForecast;
import com.budgetintel.client.MlDtos.MlHealth;
import com.budgetintel.client.MlDtos.MlIndicator;
import com.budgetintel.client.MlDtos.MlModelVersion;
import com.budgetintel.client.MlDtos.MlObservations;
import com.budgetintel.client.MlDtos.MlState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/** HTTP client for the FastAPI ML service. All calls go through {@link #call} for uniform error mapping. */
@Component
public class MlServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public MlServiceClient(RestClient.Builder builder, MlServiceProperties props, ObjectMapper mapper) {
        this(builder, props.baseUrl(), timeouts(props), mapper, props.apiKey(),
                props.gcpIdToken() ? new GcpIdTokenProvider(props.baseUrl()) : null);
    }

    /** @param factory request factory, or null to keep the builder's (tests bind a mock server to the builder) */
    MlServiceClient(RestClient.Builder builder, String baseUrl, ClientHttpRequestFactory factory, ObjectMapper mapper) {
        this(builder, baseUrl, factory, mapper, null, null);
    }

    /**
     * @param apiKey   sent as X-ML-Api-Key when not blank
     * @param idTokens when not null, adds "Authorization: Bearer &lt;Google ID token&gt;" (private Cloud Run service)
     */
    MlServiceClient(RestClient.Builder builder, String baseUrl, ClientHttpRequestFactory factory, ObjectMapper mapper,
                    String apiKey, GcpIdTokenProvider idTokens) {
        this.mapper = mapper;
        RestClient.Builder b = factory == null ? builder : builder.clone().requestFactory(factory);
        this.http = b.baseUrl(baseUrl)
                .requestInterceptor((req, body, exec) -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        req.getHeaders().set("X-ML-Api-Key", apiKey);
                    }
                    if (idTokens != null) {
                        req.getHeaders().setBearerAuth(idTokens.token());
                    }
                    return exec.execute(req, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw toException(res.getStatusCode().value(), res.getBody().readAllBytes());
                })
                .build();
    }

    private static ClientHttpRequestFactory timeouts(MlServiceProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeoutMs());
        factory.setReadTimeout(props.readTimeoutMs());
        return factory;
    }

    MlServiceException toException(int status, byte[] body) {
        try {
            MlError e = mapper.readValue(body, MlError.class);
            if (e.error() != null) {
                return new MlServiceException(status, e.error(), e.message(), e.details());
            }
        } catch (IOException ignored) {
            // fall through to a generic error
        }
        return new MlServiceException(status, "ML_SERVICE_ERROR", "ML service returned HTTP " + status, List.of());
    }

    private <T> T call(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (MlServiceException e) {
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("ML service unreachable during {}: {}", what, e.getMessage());
            throw new MlServiceUnavailableException("The ML service is not reachable", e);
        } catch (RestClientException e) {
            log.error("ML service call {} failed", what, e);
            throw new MlServiceUnavailableException("The ML service returned an unreadable response", e);
        }
    }

    private <T> T get(String what, java.util.function.Function<UriBuilder, java.net.URI> uri, Class<T> type) {
        return call(what, () -> http.get().uri(uri).retrieve().body(type));
    }

    private <T> T post(String path, Object body, Class<T> type) {
        return call(path, () -> http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(type));
    }

    private Map<String, Object> postMap(String path, Object body) {
        return call(path, () -> http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(MAP));
    }

    // ------------------------------------------------------------------------------------------- reference

    public MlHealth health() {
        return get("health", u -> u.path("/health").build(), MlHealth.class);
    }

    public List<MlState> states() {
        return List.of(get("states", u -> u.path("/states").build(), MlState[].class));
    }

    public List<MlIndicator> indicators() {
        return List.of(get("indicators", u -> u.path("/indicators").build(), MlIndicator[].class));
    }

    public MlDataset dataset() {
        return get("dataset", u -> u.path("/dataset").build(), MlDataset.class);
    }

    public MlObservations observations() {
        return get("observations", u -> u.path("/dataset/observations").build(), MlObservations.class);
    }

    public Map<String, Object> baseline(String stateCode, String fiscalYear) {
        return call("baseline", () -> http.get().uri(u -> {
            u.path("/baseline").queryParam("state_code", stateCode);
            if (fiscalYear != null) {
                u.queryParam("fiscal_year", fiscalYear);
            }
            return u.build();
        }).retrieve().body(MAP));
    }

    public List<Map<String, Object>> impact() {
        return call("impact", () -> http.get().uri("/impact").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    // ---------------------------------------------------------------------------------------------- models

    public List<MlModelVersion> models() {
        return List.of(get("models", u -> u.path("/models").build(), MlModelVersion[].class));
    }

    public MlBacktest backtest(String target, String modelType, Integer horizon) {
        return get("backtest", u -> {
            u.path("/backtest").queryParam("target", target).queryParam("model_type", modelType);
            if (horizon != null) {
                u.queryParam("horizon", horizon);
            }
            return u.build();
        }, MlBacktest.class);
    }

    public MlModelVersion calibrate(String target, List<MlDeviationRecord> calibration, List<MlDeviationRecord> evaluation) {
        return post("/calibrate", Map.of("target", target, "calibration_records", calibration,
                "evaluation_records", evaluation), MlModelVersion.class);
    }

    public MlModelVersion retrain(String target, boolean activateIfBetter) {
        return post("/retrain", Map.of("target", target, "activate_if_better", activateIfBetter), MlModelVersion.class);
    }

    // -------------------------------------------------------------------------------------------- compute

    public MlForecast forecast(String stateCode, String target, int horizon) {
        return post("/forecast", Map.of("state_code", stateCode, "target", target, "horizon", horizon), MlForecast.class);
    }

    public Map<String, Object> scenario(Map<String, Object> request) {
        return postMap("/scenario", withoutNulls(request));
    }

    public Map<String, Object> optimize(Map<String, Object> request) {
        return postMap("/optimize", withoutNulls(request));
    }

    public Map<String, Object> stressTest(Map<String, Object> request) {
        return postMap("/stress-test", withoutNulls(request));
    }

    public MlEvaluate evaluate(List<MlEvaluateRecord> records) {
        return post("/evaluate", Map.of("records", records), MlEvaluate.class);
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        Map<String, Object> out = new HashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }
}
