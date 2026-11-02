package com.budgetintel.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Prediction accuracy, model versions and the dashboard. */
public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    public record DeviationDto(Long predictionId, String stateCode, String indicator, String originFiscalYear,
                               String fiscalYear, int horizon, String kind, int modelVersion, String modelType,
                               String comparisonBasis, BigDecimal predictedValue, BigDecimal actualValue,
                               BigDecimal absoluteError, BigDecimal percentageError, OffsetDateTime evaluatedAt) {
    }

    public record AccuracySummaryDto(String indicator, int modelVersion, String modelType, String comparisonBasis,
                                     String kind, int horizon, long n, BigDecimal meanAbsoluteError,
                                     BigDecimal meanAbsolutePercentageError, BigDecimal meanSignedPercentageError) {
    }

    public record EvaluationResult(int forecastsChecked, int deviationsAdded, Map<String, Integer> addedByBasis) {
    }

    public record ModelVersionDto(String indicator, int version, String modelType, boolean active, boolean calibrated,
                                  OffsetDateTime trainedAt, String datasetLabel, String trainYearFrom,
                                  String trainYearTo, Integer parentVersion, Map<String, Object> metrics,
                                  Map<String, Object> config) {
    }

    public record SectorShare(String sector, String label, BigDecimal amount, BigDecimal sharePct,
                              BigDecimal perCapita) {
    }

    public record DashboardDto(String stateCode, String stateName, String fiscalYear, String dataStatus,
                               BigDecimal totalExpenditure, BigDecimal discretionaryExpenditure,
                               BigDecimal committedExpenditure, BigDecimal revenueReceipts, BigDecimal ownTaxRevenue,
                               BigDecimal grossFiscalDeficit, BigDecimal gfdPctGsdp, BigDecimal deficitLimitUsedPct,
                               boolean healthEstimated, List<SectorShare> sectors,
                               List<AnalysisDtos.ForecastResponse> forecasts, List<String> notes) {
    }
}
