package com.budgetintel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetintel.client.MlDtos.MlBacktest;
import com.budgetintel.client.MlDtos.MlBacktestRow;
import com.budgetintel.client.MlDtos.MlDataset;
import com.budgetintel.client.MlDtos.MlDeviation;
import com.budgetintel.client.MlDtos.MlDeviationRecord;
import com.budgetintel.client.MlDtos.MlEvaluate;
import com.budgetintel.client.MlDtos.MlEvaluateRecord;
import com.budgetintel.client.MlDtos.MlForecast;
import com.budgetintel.client.MlDtos.MlIndicator;
import com.budgetintel.client.MlDtos.MlModelRef;
import com.budgetintel.client.MlDtos.MlModelVersion;
import com.budgetintel.client.MlDtos.MlObservation;
import com.budgetintel.client.MlDtos.MlObservations;
import com.budgetintel.client.MlDtos.MlPrediction;
import com.budgetintel.client.MlDtos.MlState;
import com.budgetintel.client.MlServiceClient;
import com.budgetintel.client.MlServiceException;
import com.budgetintel.dto.AnalysisDtos.ForecastRequest;
import com.budgetintel.dto.AnalysisDtos.ForecastResponse;
import com.budgetintel.dto.AnalysisDtos.OptimizationRequest;
import com.budgetintel.dto.AnalysisDtos.ScenarioRequest;
import com.budgetintel.dto.AnalysisDtos.StressTestRequest;
import com.budgetintel.dto.FeedbackDtos.DeviationDto;
import com.budgetintel.dto.ReferenceDtos.DatasetImportResult;
import com.budgetintel.exception.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Services and repositories against the PostgreSQL test database (budget_intel_test, schema from Flyway),
 * with the ML service mocked. Fixture values are Karnataka revenue-expenditure magnitudes, kept small.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceDatabaseTest {

    private static final String HASH = "a".repeat(64);
    private static final String T = "revenue_expenditure";

    @MockitoBean private MlServiceClient ml;
    @Autowired private DatasetImportService importer;
    @Autowired private ForecastService forecasts;
    @Autowired private FeedbackService feedback;
    @Autowired private ModelService models;
    @Autowired private OptimizationService optimizations;
    @Autowired private ScenarioService scenarios;
    @Autowired private StressTestService stress;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAndStub() {
        jdbc.execute("""
                truncate prediction_deviation, forecast, model_version, scenario_outcome, scenario_allocation,
                budget_scenario, optimization_allocation, optimization_run, stress_test_result, stress_test,
                observation, dataset_version, indicator, state restart identity cascade""");
        when(ml.dataset()).thenReturn(new MlDataset("test-ds", HASH, "2026-10-23T00:00:00+00:00", 6, 6,
                List.of("RBI HSIS 2024-25"), Map.of(), List.of(), Map.of(), 0, Map.of(), Map.of()));
        when(ml.states()).thenReturn(List.of(new MlState("KA", "Karnataka", "STATE", null, true),
                new MlState("UP", "Uttar Pradesh", "STATE", null, true)));
        when(ml.indicators()).thenReturn(List.of(new MlIndicator(T, "Revenue expenditure", "INR_CRORE", "FISCAL",
                "RBI HSIS T166", true)));
        when(ml.observations()).thenReturn(new MlObservations("test-ds", 6, List.of(
                obs("2019-20", 180000, "ACCOUNTS"), obs("2020-21", 185000, "ACCOUNTS"), obs("2021-22", 200000, "ACCOUNTS"),
                obs("2022-23", 230000, "ACTUAL"), obs("2023-24", 240731, "RE"), obs("2024-25", 290531, "BE"))));
        when(ml.models()).thenReturn(List.of(version(1, true, null, null), version(2, false, 1, Map.of("panel_bias", 0.01))));
        when(ml.backtest(eq(T), eq("ridge_macro"), isNull())).thenReturn(new MlBacktest(T, "ridge_macro", 4, List.of(
                bt("2018-19", 1, "2019-20", 180000, 171000), bt("2019-20", 1, "2020-21", 185000, 190000),
                bt("2020-21", 1, "2021-22", 200000, 194000), bt("2021-22", 1, "2022-23", 230000, 214000))));
        when(ml.evaluate(anyList())).thenAnswer(inv -> {
            List<MlEvaluateRecord> recs = inv.getArgument(0);
            List<MlDeviation> out = new ArrayList<>();
            for (MlEvaluateRecord r : recs) {
                out.add(new MlDeviation(r.predictionId(), r.predicted(), r.actual(), Math.abs(r.predicted() - r.actual()),
                        100 * (r.predicted() - r.actual()) / r.actual()));
            }
            return new MlEvaluate(out, List.of());
        });
    }

    private static MlObservation obs(String fy, double v, String status) {
        return new MlObservation("KA", T, fy, v, status, null, "RBI HSIS T166");
    }

    private static MlBacktestRow bt(String origin, int h, String target, double actual, double predicted) {
        return new MlBacktestRow(T, "ridge_macro", "KA", origin, h, target, actual, predicted);
    }

    private static MlModelVersion version(int v, boolean active, Integer parent, Map<String, Object> calibration) {
        return new MlModelVersion(T, v, "ridge_macro", active, "2026-10-23T10:00:00+00:00", "test-ds", HASH, "2008-09",
                "2022-23", parent, List.of("g1"), Map.of(), Map.of("mape", 4.56), Map.of(), Map.of(), Map.of(), Map.of(),
                calibration, null, null, "models/" + T + "/v" + v);
    }

    @Test
    void importLoadsDataScoresBacktestsAndIsIdempotent() {
        DatasetImportResult first = importer.importCurrent();
        assertThat(first.newDataset()).isTrue();
        assertThat(first.observations()).isEqualTo(6);
        assertThat(first.modelVersions()).isEqualTo(2);
        assertThat(first.backtestForecasts()).isEqualTo(4);
        assertThat(first.deviationsAdded()).isEqualTo(4);

        DatasetImportResult second = importer.importCurrent();
        assertThat(second.newDataset()).isFalse();
        assertThat(second.backtestForecasts()).isZero();
        assertThat(second.deviationsAdded()).isZero();

        var listed = models.list(T);
        assertThat(listed).hasSize(2);
        assertThat(listed.get(1).parentVersion()).isEqualTo(1);
        assertThat(listed.get(1).calibrated()).isTrue();
        assertThat(listed).filteredOn(m -> m.active()).singleElement().satisfies(m -> assertThat(m.version()).isEqualTo(1));

        List<DeviationDto> devs = feedback.search("KA", T, null, null, null);
        DeviationDto d = devs.stream().filter(x -> x.fiscalYear().equals("2022-23")).findFirst().orElseThrow();
        assertThat(d.comparisonBasis()).isEqualTo("ACTUAL");
        assertThat(d.absoluteError().doubleValue()).isEqualTo(16000.0);
        assertThat(d.percentageError().doubleValue()).isCloseTo(-6.9565, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(feedback.summary()).singleElement().satisfies(s -> assertThat(s.n()).isEqualTo(4));
    }

    @Test
    void forecastStoresLivePredictionsOnceAndComparesWithGovernmentEstimates() {
        importer.importCurrent();
        when(ml.forecast("KA", T, 2)).thenReturn(new MlForecast("KA", T, "INR_CRORE", "2022-23",
                new MlModelRef(T, 1, "ridge_macro", false), List.of(), List.of(),
                List.of(new MlPrediction("2023-24", 1, 247150, 247150, 223112.0, 261434.0),
                        new MlPrediction("2024-25", 2, 278257, 278257, 245554.0, 295870.0)), Map.of(), "80%"));
        ForecastResponse a = forecasts.generate(new ForecastRequest("KA", T, 2));
        ForecastResponse b = forecasts.generate(new ForecastRequest("KA", T, 2));
        assertThat(b.predictions()).extracting(p -> p.predictionId())
                .containsExactlyElementsOf(a.predictions().stream().map(p -> p.predictionId()).toList());

        List<DeviationDto> live = feedback.search("KA", T, null, null, com.budgetintel.entity.Enums.ForecastKind.LIVE);
        assertThat(live).extracting(DeviationDto::comparisonBasis).containsExactly("GOVT_RE", "GOVT_BE");
        assertThat(live.get(0).percentageError().doubleValue())
                .isCloseTo(100 * (247150 - 240731) / 240731.0, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void forecastBeforeImportIsRejected() {
        assertThatThrownBy(() -> forecasts.generate(new ForecastRequest("KA", T, 1)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("NO_DATASET"));
    }

    @Test
    void calibrationHoldsOutTheLatestTwoYears() {
        importer.importCurrent();
        when(ml.calibrate(eq(T), anyList(), anyList())).thenReturn(version(3, true, 1, Map.of("panel_bias", 0.02)));
        when(ml.models()).thenReturn(List.of(version(1, false, null, null), version(2, false, 1, Map.of("panel_bias", 0.01)),
                version(3, true, 1, Map.of("panel_bias", 0.02))));
        var dto = models.calibrate(T);
        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.active()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MlDeviationRecord>> cal = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MlDeviationRecord>> ev = ArgumentCaptor.forClass(List.class);
        verify(ml).calibrate(eq(T), cal.capture(), ev.capture());
        assertThat(cal.getValue()).extracting(MlDeviationRecord::targetYear).containsExactly("2019-20", "2020-21");
        assertThat(ev.getValue()).extracting(MlDeviationRecord::targetYear).containsExactly("2021-22", "2022-23");
    }

    @Test
    void infeasibleOptimizationIsStoredWithReasons() {
        importer.importCurrent();
        when(ml.optimize(any())).thenThrow(new MlServiceException(422, "INFEASIBLE_CONSTRAINTS",
                "No allocation satisfies the constraints", List.of("sum of minimums exceeds total")));
        var dto = optimizations.run(new OptimizationRequest("UP", null, "MIN_ADJUSTMENT", null, null, "MEDIAN", true));
        assertThat(dto.status()).isEqualTo("INFEASIBLE");
        assertThat(dto.details()).containsExactly("sum of minimums exceeds total");
        assertThat(optimizations.get(dto.id()).status()).isEqualTo("INFEASIBLE");
    }

    @Test
    void stressTestUsesTheSavedScenarioAllocation() {
        importer.importCurrent();
        Map<String, Object> snapshot = new HashMap<>(Map.of(
                "fiscal_year", "2024-25", "baseline_data_status", "BE", "description", "custom % adjustments",
                "fiscal_effects", Map.of(
                        "baseline", Map.of("gross_fiscal_deficit", 82981.0, "gfd_pct_gsdp", 2.877, "breaches_frbm_gfd_limit", false),
                        "scenario", Map.of("gross_fiscal_deficit", 90916.45, "gfd_pct_gsdp", 3.152, "breaches_frbm_gfd_limit", true)),
                "sectors", List.of(
                        Map.of("sector", "social_sector", "baseline", 154971.0, "scenario", 154971.0, "change_pct", 0.0),
                        Map.of("sector", "capital_outlay", "baseline", 52903.0, "scenario", 60838.45, "change_pct", 15.0),
                        Map.of("sector", "other_revenue", "baseline", 63971.0, "scenario", 63971.0, "change_pct", 0.0)),
                "indicator_effects", List.of()));
        when(ml.scenario(any())).thenReturn(snapshot);
        var sc = scenarios.create(new ScenarioRequest("KA capital +15%", "KA", null, "CUSTOM", null,
                Map.of("capital_outlay", 15.0), null));
        assertThat(scenarios.list("KA")).singleElement().satisfies(s -> assertThat(s.breachesFrbmLimit()).isTrue());

        when(ml.stressTest(any())).thenReturn(Map.of("fiscal_year", "2024-25", "calibration", Map.of("revenue_shock_pct", -5.2),
                "results", List.of(Map.of("scenario", "DOWNTURN", "metric", "gfd_pct_gsdp", "normal", 3.15, "stressed", 4.03,
                        "breaches_limit", true))));
        var st = stress.run(new StressTestRequest("KA", null, "SCENARIO", sc.id(), null, List.of("DOWNTURN")));
        assertThat(st.sourceId()).isEqualTo(sc.id());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(ml).stressTest(body.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> alloc = (Map<String, Object>) body.getValue().get("allocation");
        assertThat(((Number) alloc.get("capital_outlay")).doubleValue()).isEqualTo(60838.45);
        assertThat(body.getValue()).containsEntry("fiscal_year", "2024-25");

        assertThatThrownBy(() -> stress.run(new StressTestRequest("UP", null, "SCENARIO", sc.id(), null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("belongs to KA");
        assertThatThrownBy(() -> stress.run(new StressTestRequest("KA", null, "SCENARIO", null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("source_id is required");
    }

    @Test
    void unknownStateAndNonTargetIndicator() {
        importer.importCurrent();
        assertThatThrownBy(() -> forecasts.generate(new ForecastRequest("XX", T, 1))).isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown state");
        when(ml.forecast(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("must not be called"));
        assertThatThrownBy(() -> forecasts.generate(new ForecastRequest("KA", "gdp", 1))).isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown indicator");
    }
}
