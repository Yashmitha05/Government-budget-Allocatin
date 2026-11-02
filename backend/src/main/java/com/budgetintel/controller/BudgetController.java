package com.budgetintel.controller;

import static com.budgetintel.dto.AnalysisDtos.FISCAL_YEAR;
import static com.budgetintel.dto.AnalysisDtos.STATE_CODE;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.FeedbackDtos.DashboardDto;
import com.budgetintel.dto.ReferenceDtos.BudgetRecordDto;
import com.budgetintel.dto.ReferenceDtos.YearComparisonDto;
import com.budgetintel.service.BudgetRecordService;
import com.budgetintel.service.DashboardService;
import com.budgetintel.service.ReferenceDataService;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class BudgetController {

    private final BudgetRecordService records;
    private final DashboardService dashboard;
    private final ReferenceDataService reference;
    private final MlServiceClient ml;

    public BudgetController(BudgetRecordService records, DashboardService dashboard, ReferenceDataService reference,
                            MlServiceClient ml) {
        this.records = records;
        this.dashboard = dashboard;
        this.reference = reference;
        this.ml = ml;
    }

    @GetMapping("/budget-records")
    public List<BudgetRecordDto> search(
            @RequestParam("state") @Pattern(regexp = STATE_CODE, message = "must be a state code") String state,
            @RequestParam(value = "indicator", required = false) String indicator,
            @RequestParam(value = "from_year", required = false) @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String fromYear,
            @RequestParam(value = "to_year", required = false) @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String toYear) {
        return records.search(state, indicator, fromYear, toYear);
    }

    @GetMapping("/budget-records/compare")
    public YearComparisonDto compare(
            @RequestParam("state") @Pattern(regexp = STATE_CODE, message = "must be a state code") String state,
            @RequestParam("year") @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String year,
            @RequestParam(value = "previous_year", required = false) @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String previousYear) {
        return records.compare(state, year, previousYear);
    }

    /** Sector split and fiscal position of one budget year (default: latest). */
    @GetMapping("/budget-records/baseline")
    public Map<String, Object> baseline(
            @RequestParam("state") @Pattern(regexp = STATE_CODE, message = "must be a state code") String state,
            @RequestParam(value = "year", required = false) @Pattern(regexp = FISCAL_YEAR, message = "must look like 2024-25") String year) {
        reference.currentDataset();
        reference.requireState(state);
        return ml.baseline(state, year);
    }

    @GetMapping("/dashboard/summary")
    public DashboardDto summary(
            @RequestParam("state") @Pattern(regexp = STATE_CODE, message = "must be a state code") String state) {
        return dashboard.summary(state);
    }
}
