package com.budgetintel.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.budgetintel.client.MlDtos.MlForecast;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MlServiceClientTest {

    private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockRestServiceServer server;
    private MlServiceClient client;

    private RestClient.Builder builder() {
        return RestClient.builder().messageConverters(c -> {
            c.removeIf(m -> m instanceof MappingJackson2HttpMessageConverter);
            c.add(new MappingJackson2HttpMessageConverter(mapper));
        });
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder b = builder();
        server = MockRestServiceServer.bindTo(b).build();
        client = new MlServiceClient(b, "http://ml", null, mapper);
    }

    @Test
    void forecastRequestAndResponseUseSnakeCase() {
        server.expect(requestTo("http://ml/forecast")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"state_code\":\"KA\",\"target\":\"revenue_expenditure\",\"horizon\":2}"))
                .andRespond(withSuccess("""
                        {"state_code":"KA","target":"revenue_expenditure","unit":"INR_CRORE","origin_fiscal_year":"2022-23",
                         "model":{"target":"revenue_expenditure","version":1,"model_type":"ridge_macro","calibrated":false},
                         "history":[{"fiscal_year":"2022-23","value":230000.0,"data_status":"ACTUAL"}],
                         "government_estimates":[],
                         "predictions":[{"fiscal_year":"2023-24","horizon":1,"value":247150.0,"uncalibrated_value":247150.0,
                                         "lower":223112.0,"upper":261434.0}],
                         "backtest_metrics":{"mape":4.56},"interval_note":"80%"}""", MediaType.APPLICATION_JSON));
        MlForecast f = client.forecast("KA", "revenue_expenditure", 2);
        assertThat(f.originFiscalYear()).isEqualTo("2022-23");
        assertThat(f.model().modelType()).isEqualTo("ridge_macro");
        assertThat(f.predictions().get(0).lower()).isEqualTo(223112.0);
        server.verify();
    }

    @Test
    void mlErrorBodyBecomesTypedException() {
        server.expect(requestTo("http://ml/forecast")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"INSUFFICIENT_HISTORY\",\"message\":\"TG needs 4 years\",\"details\":[\"x\"]}"));
        assertThatThrownBy(() -> client.forecast("TG", "revenue_expenditure", 1))
                .isInstanceOfSatisfying(MlServiceException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_HISTORY");
                    assertThat(e.getDetails()).containsExactly("x");
                });
    }

    @Test
    void nonJsonServerErrorBecomesGenericException() {
        server.expect(requestTo("http://ml/health")).andRespond(withServerError().body("<html>boom</html>"));
        assertThatThrownBy(client::health)
                .isInstanceOfSatisfying(MlServiceException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("ML_SERVICE_ERROR");
                });
    }

    @Test
    void unreachableServiceIsReportedAsUnavailable() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(500);
        MlServiceClient down = new MlServiceClient(builder(), "http://127.0.0.1:1", f, mapper);
        assertThatThrownBy(down::health).isInstanceOf(MlServiceUnavailableException.class);
    }

    @Test
    void sendsServiceKeyAndIdTokenWhenConfigured() {
        RestClient.Builder b = builder();
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        GcpIdTokenProvider tokens = new GcpIdTokenProvider("https://ml.example.run.app/", url -> "id-token-1",
                java.time.Clock.systemUTC());
        MlServiceClient authed = new MlServiceClient(b, "http://ml", null, mapper, "s3cret", tokens);
        s.expect(requestTo("http://ml/health"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-ML-Api-Key", "s3cret"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer id-token-1"))
                .andRespond(withSuccess("{\"status\":\"ok\",\"dataset_label\":\"x\",\"panel_rows\":1,\"active_models\":{}}",
                        MediaType.APPLICATION_JSON));
        assertThat(authed.health().status()).isEqualTo("ok");
        s.verify();
    }

    @Test
    void noAuthHeadersByDefault() {
        server.expect(requestTo("http://ml/health"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("X-ML-Api-Key"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"status\":\"ok\",\"dataset_label\":\"x\",\"panel_rows\":1,\"active_models\":{}}",
                        MediaType.APPLICATION_JSON));
        client.health();
        server.verify();
    }

    @Test
    void idTokenIsCachedAndRefreshedAfterFiftyMinutes() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        java.util.List<String> urls = new java.util.ArrayList<>();
        GcpIdTokenProvider p = new GcpIdTokenProvider("https://ml.example.run.app/",
                url -> { urls.add(url); return "t" + calls.incrementAndGet(); }, clock);
        assertThat(p.token()).isEqualTo("t1");
        now.set(now.get().plusSeconds(49 * 60));
        assertThat(p.token()).isEqualTo("t1");
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(p.token()).isEqualTo("t2");
        assertThat(urls.get(0)).endsWith("audience=https%3A%2F%2Fml.example.run.app");
    }

    @Test
    void nullFieldsAreDroppedFromComputeRequests() {
        server.expect(requestTo("http://ml/optimize"))
                .andExpect(content().json("{\"state_code\":\"PB\",\"objective\":\"MIN_ADJUSTMENT\"}", true))
                .andRespond(withSuccess("{\"status\":\"OPTIMAL\"}", MediaType.APPLICATION_JSON));
        java.util.Map<String, Object> req = new java.util.HashMap<>();
        req.put("state_code", "PB");
        req.put("objective", "MIN_ADJUSTMENT");
        req.put("total_budget", null);
        assertThat(client.optimize(req)).containsEntry("status", "OPTIMAL");
    }
}
