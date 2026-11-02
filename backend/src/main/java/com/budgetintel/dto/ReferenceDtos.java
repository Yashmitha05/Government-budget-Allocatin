package com.budgetintel.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    public record StateDto(String code, String name, String category, String formedFiscalYear, boolean hasBudgetData) {
    }

    public record IndicatorDto(String code, String name, String unit, String category, String sourceTable,
                               boolean forecastTarget) {
    }

    public record DatasetDto(String label, String contentHash, String builtAt, OffsetDateTime importedAt,
                             int recordCount, String sourceSummary, Map<String, Object> qualityReport) {
    }

    public record DatasetImportResult(String datasetLabel, boolean newDataset, long observations, int states,
                                      int indicators, int modelVersions, int backtestForecasts, int deviationsAdded) {
    }

    public record BudgetRecordDto(String stateCode, String indicator, String indicatorName, String unit,
                                  String fiscalYear, BigDecimal value, String dataStatus, String footnoteFlag) {
    }

    public record ComparisonRow(String indicator, String indicatorName, String unit, BigDecimal previousValue,
                                String previousStatus, BigDecimal currentValue, String currentStatus,
                                BigDecimal change, BigDecimal changePct) {
    }

    public record YearComparisonDto(String stateCode, String stateName, String fiscalYear, String previousFiscalYear,
                                    List<ComparisonRow> rows) {
    }
}
