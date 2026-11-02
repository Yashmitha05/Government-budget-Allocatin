package com.budgetintel.service;

import static com.budgetintel.service.Num.dec;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.AnalysisDtos.ForecastRequest;
import com.budgetintel.dto.AnalysisDtos.ForecastResponse;
import com.budgetintel.dto.FeedbackDtos.DashboardDto;
import com.budgetintel.dto.FeedbackDtos.SectorShare;
import com.budgetintel.entity.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Dashboard summary for one state's latest budget year.
 * "Deficit limit used" = GFD/GSDP as a share of the 3% FRBM ceiling. Actual-vs-budget utilisation cannot be
 * computed from this dataset (each year is published with one status: Accounts, RE or BE), so it is not shown.
 */
@Service
public class DashboardService {

    static final List<String> FORECAST_INDICATORS = List.of("revenue_expenditure", "own_tax_revenue");
    private static final BigDecimal FRBM_LIMIT = BigDecimal.valueOf(3);
    private static final Map<String, String> LABELS = Map.of(
            "social_sector", "Social sector",
            "capital_outlay", "Capital outlay (infrastructure)",
            "other_revenue", "Other revenue expenditure");

    private final MlServiceClient ml;
    private final ReferenceDataService reference;
    private final ForecastService forecasts;

    public DashboardService(MlServiceClient ml, ReferenceDataService reference, ForecastService forecasts) {
        this.ml = ml;
        this.reference = reference;
        this.forecasts = forecasts;
    }

    public DashboardDto summary(String stateCode) {
        reference.currentDataset();
        State state = reference.requireState(stateCode);
        Map<String, Object> b = ml.baseline(state.getCode(), null);

        BigDecimal discretionary = dec(b.get("discretionary"), 2);
        List<SectorShare> sectors = new ArrayList<>();
        BigDecimal population = dec(b.get("population"));
        for (String k : List.of("social_sector", "capital_outlay", "other_revenue")) {
            BigDecimal amount = dec(b.get(k), 2);
            BigDecimal perCapita = population == null || population.signum() == 0 ? null
                    : amount.multiply(BigDecimal.valueOf(1e7)).divide(population, 0, RoundingMode.HALF_UP);
            sectors.add(new SectorShare(k, LABELS.get(k), amount,
                    amount.multiply(BigDecimal.valueOf(100)).divide(discretionary, 2, RoundingMode.HALF_UP), perCapita));
        }
        BigDecimal gfdPct = dec(b.get("gfd_pct_gsdp"), 4);
        BigDecimal limitUsed = gfdPct == null ? null
                : gfdPct.multiply(BigDecimal.valueOf(100)).divide(FRBM_LIMIT, 2, RoundingMode.HALF_UP);
        BigDecimal total = dec(b.get("revenue_expenditure"), 2).add(dec(b.get("capital_outlay"), 2));

        List<ForecastResponse> fc = FORECAST_INDICATORS.stream()
                .map(i -> forecasts.generate(new ForecastRequest(state.getCode(), i, 3))).toList();

        List<String> notes = new ArrayList<>();
        notes.add("Total expenditure = revenue expenditure + capital outlay (deficit-relevant spending).");
        notes.add("Figures for " + b.get("fiscal_year") + " are " + statusLabel((String) b.get("data_status")) + ".");
        return new DashboardDto(state.getCode(), state.getName(), (String) b.get("fiscal_year"), (String) b.get("data_status"),
                total, discretionary, dec(b.get("committed"), 2), dec(b.get("revenue_receipts"), 2),
                dec(b.get("own_tax_revenue"), 2), dec(b.get("gross_fiscal_deficit"), 2), gfdPct, limitUsed,
                Boolean.TRUE.equals(b.get("health_estimated")), sectors, fc, notes);
    }

    private static String statusLabel(String s) {
        return switch (s) {
            case "BE" -> "Budget Estimates";
            case "RE" -> "Revised Estimates";
            case "ACTUAL", "ACCOUNTS" -> "actual accounts";
            default -> s;
        };
    }
}
