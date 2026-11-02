package com.budgetintel.client;

import java.util.List;
import java.util.Map;

/**
 * Typed views of ML service responses that the backend reads field by field. JSON is snake_case
 * (global Jackson naming strategy); unknown fields are ignored. Scenario, optimisation and stress-test
 * responses are kept as maps because they are stored verbatim as result snapshots.
 */
public final class MlDtos {

    private MlDtos() {
    }

    public record MlError(String error, String message, List<String> details) {
    }

    public record MlHealth(String status, String datasetLabel, int panelRows, Map<String, Integer> activeModels) {
    }

    public record MlState(String code, String name, String category, String formedFiscalYear, boolean hasBudgetData) {
    }

    public record MlIndicator(String code, String name, String unit, String category, String sourceTable,
                              boolean isForecastTarget) {
    }

    public record MlDataset(String label, String contentHash, String builtAt, int recordCount, int panelRows,
                            List<String> sources, Map<String, String> sourceFiles, List<Map<String, Object>> coverage,
                            Map<String, Object> aggregateCheck, int outlierFlagCount,
                            Map<String, Double> populationSumVsWdi, Map<String, List<String>> structuralBreaks) {
    }

    public record MlObservation(String stateCode, String indicator, String fiscalYear, double value, String dataStatus,
                                String footnoteFlag, String sourceTable) {
    }

    public record MlObservations(String datasetLabel, int count, List<MlObservation> observations) {
    }

    public record MlModelVersion(String target, int version, String modelType, boolean isActive, String trainedAt,
                                 String datasetLabel, String datasetHash, String trainYearFrom, String trainYearTo,
                                 Integer parentVersion, List<String> features, Map<String, Object> params,
                                 Map<String, Object> metrics, Map<String, Object> selectionMapeH1,
                                 Map<String, Object> holdoutMetrics, Map<String, Object> intervalQuantiles,
                                 Map<String, Object> intervalCoverageHoldout, Map<String, Object> calibration,
                                 Map<String, Object> calibrationEvaluation, Map<String, Object> comparison,
                                 String artifactPath) {
    }

    public record MlModelRef(String target, int version, String modelType, boolean calibrated) {
    }

    public record MlPoint(String fiscalYear, double value, String dataStatus) {
    }

    public record MlPrediction(String fiscalYear, int horizon, double value, double uncalibratedValue, Double lower,
                               Double upper) {
    }

    public record MlForecast(String stateCode, String target, String unit, String originFiscalYear, MlModelRef model,
                             List<MlPoint> history, List<MlPoint> governmentEstimates, List<MlPrediction> predictions,
                             Map<String, Object> backtestMetrics, String intervalNote) {
    }

    public record MlBacktestRow(String target, String model, String stateCode, String origin, int horizon,
                                String targetYear, double actual, double predicted) {
    }

    public record MlBacktest(String target, String modelType, int count, List<MlBacktestRow> rows) {
    }

    public record MlEvaluateRecord(Long predictionId, String stateCode, String target, String targetYear,
                                   Integer modelVersion, double predicted, double actual) {
    }

    public record MlDeviation(Long predictionId, double predicted, double actual, double absoluteError,
                              double percentageError) {
    }

    public record MlEvaluate(List<MlDeviation> records, List<Map<String, Object>> groups) {
    }

    public record MlDeviationRecord(String stateCode, String targetYear, double predicted, double actual) {
    }
}
