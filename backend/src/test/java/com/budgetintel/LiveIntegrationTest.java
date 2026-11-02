package com.budgetintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end over HTTP: backend -> PostgreSQL (test database) -> the running FastAPI ML service -> real models,
 * on the real RBI/World Bank dataset. Requires the ML service on ML_SERVICE_URL (default localhost:8000).
 * Does not call retrain/calibrate, which would add versions to the real model registry.
 * Run: mvn test -Dgroups=live -DexcludedTestGroups=none
 */
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveIntegrationTest {

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void cleanDatabase() {
        jdbc.execute("""
                truncate prediction_deviation, forecast, model_version, scenario_outcome, scenario_allocation,
                budget_scenario, optimization_allocation, optimization_run, stress_test_result, stress_test,
                observation, dataset_version, indicator, state restart identity cascade""");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, HttpStatus expected) {
        ResponseEntity<Map> r = http.postForEntity(path, body, Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(expected);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String path, Class<T> type) {
        ResponseEntity<T> r = http.getForEntity(path, type);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    @Order(1)
    void importRealDataset() {
        Map<String, Object> r = post("/api/admin/datasets/import", null, HttpStatus.OK);
        assertThat(r.get("new_dataset")).isEqualTo(true);
        assertThat(((Number) r.get("observations")).intValue()).isEqualTo(14730);
        assertThat(((Number) r.get("model_versions")).intValue()).isGreaterThanOrEqualTo(10);
        assertThat(((Number) r.get("backtest_forecasts")).intValue()).isGreaterThan(2000);
        assertThat(r.get("deviations_added")).isEqualTo(r.get("backtest_forecasts"));
        assertThat(post("/api/admin/datasets/import", null, HttpStatus.OK).get("new_dataset")).isEqualTo(false);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void budgetRecordsMatchPublishedRbiValues() {
        List<Map<String, Object>> rows = get("/api/budget-records?state=KA&indicator=gross_fiscal_deficit&from_year=2024-25", List.class);
        assertThat(rows).singleElement().satisfies(x -> {
            assertThat(((Number) x.get("value")).doubleValue()).isEqualTo(82981.0);
            assertThat(x.get("data_status")).isEqualTo("BE");
        });
        Map<String, Object> cmp = get("/api/budget-records/compare?state=KA&year=2024-25", Map.class);
        assertThat(cmp.get("previous_fiscal_year")).isEqualTo("2023-24");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void dashboardAndForecast() {
        Map<String, Object> d = get("/api/dashboard/summary?state=KA", Map.class);
        assertThat(d.get("fiscal_year")).isEqualTo("2024-25");
        assertThat((List<?>) d.get("sectors")).hasSize(3);
        assertThat((List<?>) d.get("forecasts")).hasSize(2);

        Map<String, Object> f = post("/api/forecasts", Map.of("state_code", "MH", "indicator", "social_sector_expenditure",
                "horizon", 2), HttpStatus.OK);
        assertThat(f.get("origin_fiscal_year")).isEqualTo("2022-23");
        List<Map<String, Object>> preds = (List<Map<String, Object>>) f.get("predictions");
        assertThat(preds).hasSize(2).allSatisfy(p -> assertThat(p.get("prediction_id")).isNotNull());

        List<Map<String, Object>> govt = get("/api/feedback/deviations?state=MH&indicator=social_sector_expenditure&kind=LIVE", List.class);
        assertThat(govt).extracting(x -> x.get("comparison_basis")).containsExactly("GOVT_RE", "GOVT_BE");
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void scenarioOptimizationAndStressTest() {
        Map<String, Object> sc = post("/api/scenarios", Map.of("name", "KA capital +15%", "state_code", "KA",
                "scenario_type", "CUSTOM", "adjustments_pct", Map.of("capital_outlay", 15)), HttpStatus.CREATED);
        Map<String, Object> fx = (Map<String, Object>) ((Map<String, Object>) sc.get("result")).get("fiscal_effects");
        assertThat(((Map<String, Object>) fx.get("scenario")).get("breaches_frbm_gfd_limit")).isEqualTo(true);

        Map<String, Object> opt = post("/api/optimizations", Map.of("state_code", "PB"), HttpStatus.CREATED);
        assertThat(opt.get("status")).isEqualTo("OPTIMAL");
        Map<String, Object> rec = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) opt.get("result"))
                .get("fiscal_effects")).get("recommended");
        assertThat(((Number) rec.get("gfd_pct_gsdp")).doubleValue()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-6));

        Map<String, Object> infeasible = post("/api/optimizations", Map.of("state_code", "UP", "social_floor", "MEDIAN"),
                HttpStatus.CREATED);
        assertThat(infeasible.get("status")).isEqualTo("INFEASIBLE");

        Map<String, Object> st = post("/api/stress-tests", Map.of("state_code", "PB", "source_type", "OPTIMIZATION",
                "source_id", opt.get("id"), "scenarios", List.of("COMBINED")), HttpStatus.CREATED);
        List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) st.get("result")).get("results");
        Map<String, Object> gfd = results.stream().filter(x -> "gfd_pct_gsdp".equals(x.get("metric"))).findFirst().orElseThrow();
        assertThat(((Number) gfd.get("stressed")).doubleValue()).isGreaterThan(((Number) gfd.get("normal")).doubleValue());
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void accuracySummaryAndModels() {
        List<Map<String, Object>> summary = get("/api/feedback/summary", List.class);
        assertThat(summary).anySatisfy(s -> {
            assertThat(s.get("indicator")).isEqualTo("revenue_expenditure");
            assertThat(s.get("comparison_basis")).isEqualTo("ACTUAL");
            assertThat(s.get("horizon")).isEqualTo(1);
        });
        List<Map<String, Object>> models = get("/api/models", List.class);
        assertThat(models).filteredOn(m -> Boolean.TRUE.equals(m.get("active"))).hasSize(5);
    }

    @Test
    @Order(6)
    void mlErrorsReachTheClientCleanly() {
        Map<String, Object> e = post("/api/forecasts", Map.of("state_code", "TG", "indicator", "revenue_expenditure",
                "horizon", 3), HttpStatus.OK);
        assertThat(e.get("state_code")).isEqualTo("TG");   // Telangana has enough history from 2014-15
        Map<String, Object> bad = post("/api/scenarios", Map.of("name", "x", "state_code", "KA", "scenario_type", "CUSTOM",
                "adjustments_pct", Map.of("defence", 5)), HttpStatus.BAD_REQUEST);
        assertThat(bad.get("error")).isEqualTo("VALIDATION_ERROR");
    }
}
