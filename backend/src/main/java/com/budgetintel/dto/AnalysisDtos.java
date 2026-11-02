package com.budgetintel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Requests and responses of the forecasting, scenario, optimisation and stress-test endpoints. */
public final class AnalysisDtos {

    public static final String STATE_CODE = "^[A-Z]{2,3}$";
    public static final String FISCAL_YEAR = "^\\d{4}-\\d{2}$";

    private AnalysisDtos() {
    }

    // ------------------------------------------------------------------------------------------------ forecast

    public record ForecastRequest(
            @NotBlank @Pattern(regexp = STATE_CODE, message = "must be a state code such as KA") String stateCode,
            @NotBlank String indicator,
            @Min(1) @Max(3) Integer horizon) {
    }

    public record SeriesPoint(String fiscalYear, double value, String dataStatus) {
    }

    public record PredictionDto(Long predictionId, String fiscalYear, int horizon, double value, Double lower,
                                Double upper) {
    }

    public record ModelRefDto(String indicator, int version, String modelType, boolean calibrated) {
    }

    public record ForecastResponse(String stateCode, String indicator, String unit, String originFiscalYear,
                                   ModelRefDto model, List<SeriesPoint> history, List<SeriesPoint> governmentEstimates,
                                   List<PredictionDto> predictions, Map<String, Object> backtestMetrics,
                                   String intervalNote) {
    }

    public record StoredForecastDto(Long predictionId, String stateCode, String indicator, String originFiscalYear,
                                    String targetFiscalYear, int horizon, BigDecimal predictedValue,
                                    BigDecimal lowerBound, BigDecimal upperBound, String kind, int modelVersion,
                                    String modelType, OffsetDateTime createdAt) {
    }

    // ------------------------------------------------------------------------------------------------ scenario

    public record ScenarioRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = STATE_CODE, message = "must be a state code such as KA") String stateCode,
            @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String fiscalYear,
            @NotBlank @Pattern(regexp = "BALANCED|HEALTH_PRIORITY|SOCIAL_PRIORITY|INFRASTRUCTURE_PRIORITY|CUSTOM",
                    message = "unknown scenario type") String scenarioType,
            Map<String, @NotNull @PositiveOrZero Double> allocations,
            Map<String, @NotNull @DecimalMin("-100") @DecimalMax("1000") Double> adjustmentsPct,
            @Size(max = 2000) String notes) {
    }

    public record ScenarioSummaryDto(Long id, String name, String scenarioType, String stateCode, String fiscalYear,
                                     String baselineDataStatus, BigDecimal baselineGfdPctGsdp,
                                     BigDecimal scenarioGfdPctGsdp, boolean breachesFrbmLimit, OffsetDateTime createdAt) {
    }

    public record ScenarioDto(Long id, String name, String scenarioType, String stateCode, String fiscalYear,
                              String notes, String datasetLabel, OffsetDateTime createdAt, Map<String, Object> result) {
    }

    // ------------------------------------------------------------------------------------------- optimisation

    public record SectorBound(@PositiveOrZero Double min, @PositiveOrZero Double max,
                              @DecimalMin("0") @DecimalMax("1") Double minShare,
                              @DecimalMin("0") @DecimalMax("1") Double maxShare) {
    }

    public record OptimizationRequest(
            @NotBlank @Pattern(regexp = STATE_CODE, message = "must be a state code such as KA") String stateCode,
            @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String fiscalYear,
            @Pattern(regexp = "MIN_ADJUSTMENT|MAX_CAPITAL_OUTLAY|OUTCOME", message = "unknown objective") String objective,
            @Positive Double totalBudget,
            Map<String, @Valid SectorBound> bounds,
            @Pattern(regexp = "NONE|CURRENT|MEDIAN", message = "must be NONE, CURRENT or MEDIAN") String socialFloor,
            Boolean enforceDeficitLimit) {
    }

    public record OptimizationDto(Long id, String stateCode, String fiscalYear, String objective, String status,
                                  BigDecimal totalBudget, BigDecimal objectiveValue, String message,
                                  List<String> details, String datasetLabel, OffsetDateTime createdAt,
                                  Map<String, Object> result) {
    }

    // ------------------------------------------------------------------------------------------- stress tests

    public record StressTestRequest(
            @NotBlank @Pattern(regexp = STATE_CODE, message = "must be a state code such as KA") String stateCode,
            @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String fiscalYear,
            @Pattern(regexp = "CURRENT|SCENARIO|OPTIMIZATION", message = "must be CURRENT, SCENARIO or OPTIMIZATION")
            String sourceType,
            Long sourceId,
            Map<String, Double> overrides,
            List<@Pattern(regexp = "REVENUE_SHOCK|INFLATION|POPULATION|DOWNTURN|COMBINED",
                    message = "unknown stress scenario") String> scenarios) {
    }

    public record StressTestDto(Long id, String stateCode, String fiscalYear, String sourceType, Long sourceId,
                                String datasetLabel, OffsetDateTime createdAt, Map<String, Object> result) {
    }
}
