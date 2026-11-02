package com.budgetintel.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.client.MlServiceException;
import com.budgetintel.client.MlServiceUnavailableException;
import com.budgetintel.dto.AnalysisDtos.ForecastResponse;
import com.budgetintel.dto.AnalysisDtos.ModelRefDto;
import com.budgetintel.dto.AnalysisDtos.PredictionDto;
import com.budgetintel.service.BudgetRecordService;
import com.budgetintel.service.DashboardService;
import com.budgetintel.service.ForecastService;
import com.budgetintel.service.OptimizationService;
import com.budgetintel.service.ReferenceDataService;
import com.budgetintel.service.ScenarioService;
import com.budgetintel.service.StressTestService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AnalysisController.class, BudgetController.class})
class ErrorHandlingWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ForecastService forecasts;
    @MockitoBean private ScenarioService scenarios;
    @MockitoBean private OptimizationService optimizations;
    @MockitoBean private StressTestService stressTests;
    @MockitoBean private BudgetRecordService records;
    @MockitoBean private DashboardService dashboard;
    @MockitoBean private ReferenceDataService reference;
    @MockitoBean private MlServiceClient ml;

    private static final String KA = "{\"state_code\":\"KA\",\"indicator\":\"revenue_expenditure\",\"horizon\":3}";

    @Test
    void successUsesSnakeCaseJson() throws Exception {
        when(forecasts.generate(any())).thenReturn(new ForecastResponse("KA", "revenue_expenditure", "INR_CRORE", "2022-23",
                new ModelRefDto("revenue_expenditure", 1, "ridge_macro", false), List.of(), List.of(),
                List.of(new PredictionDto(7L, "2023-24", 1, 1.0, 0.9, 1.1)), Map.of(), "note"));
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON).content(KA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin_fiscal_year").value("2022-23"))
                .andExpect(jsonPath("$.predictions[0].prediction_id").value(7))
                .andExpect(jsonPath("$.model.model_type").value("ridge_macro"));
    }

    @Test
    void beanValidationReportsJsonFieldNames() throws Exception {
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state_code\":\"ka\",\"indicator\":\"\",\"horizon\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/forecasts"))
                .andExpect(jsonPath("$.details", hasItem("horizon: must be less than or equal to 3")))
                .andExpect(jsonPath("$.details", hasItem(containsString("state_code:"))))
                .andExpect(jsonPath("$.details", hasItem(containsString("indicator:"))));
    }

    @Test
    void requestParameterValidation() throws Exception {
        mvc.perform(get("/api/budget-records").param("state", "KA").param("from_year", "2030"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("from_year: must look like 2024-25"));
        mvc.perform(get("/api/budget-records"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("state: required parameter is missing"));
    }

    @Test
    void malformedJson() throws Exception {
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void mlClientErrorsPassThrough() throws Exception {
        when(forecasts.generate(any())).thenThrow(new MlServiceException(409, "INSUFFICIENT_HISTORY", "TG needs 4 years", List.of("d")));
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON).content(KA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.details[0]").value("d"));
    }

    @Test
    void mlValidationErrorBecomes400() throws Exception {
        when(forecasts.generate(any())).thenThrow(new MlServiceException(422, "VALIDATION_ERROR", "bad", List.of()));
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON).content(KA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mlServerErrorBecomes502WithoutInternals() throws Exception {
        when(forecasts.generate(any())).thenThrow(new MlServiceException(500, "INTERNAL_ERROR", "secret detail", List.of()));
        mvc.perform(post("/api/forecasts").contentType(MediaType.APPLICATION_JSON).content(KA))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ML_SERVICE_ERROR"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void mlUnavailableBecomes503() throws Exception {
        when(dashboard.summary("KA")).thenThrow(new MlServiceUnavailableException("down", new RuntimeException()));
        mvc.perform(get("/api/dashboard/summary").param("state", "KA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ML_SERVICE_UNAVAILABLE"));
    }

    @Test
    void databaseErrorIsGeneric() throws Exception {
        when(scenarios.list(any())).thenThrow(new DataAccessResourceFailureException("connection refused to 10.0.0.5"));
        mvc.perform(get("/api/scenarios"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("DATABASE_ERROR"))
                .andExpect(content().string(not(containsString("10.0.0.5"))));
    }

    @Test
    void unexpectedErrorHasNoStackTrace() throws Exception {
        when(scenarios.get(1L)).thenThrow(new IllegalStateException("internal state xyz"));
        mvc.perform(get("/api/scenarios/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("xyz"))))
                .andExpect(content().string(not(containsString("at com."))));
    }

    @Test
    void badPathVariableAndEnumParam() throws Exception {
        mvc.perform(get("/api/scenarios/abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/forecasts").param("kind", "SOMETHING")).andExpect(status().isBadRequest());
    }
}
