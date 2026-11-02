package com.budgetintel.service;

import com.budgetintel.dto.ReferenceDtos.BudgetRecordDto;
import com.budgetintel.dto.ReferenceDtos.ComparisonRow;
import com.budgetintel.dto.ReferenceDtos.YearComparisonDto;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Observation;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.ObservationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Budget and socioeconomic records of the current dataset, and year-on-year comparison. */
@Service
@Transactional(readOnly = true)
public class BudgetRecordService {

    private static final List<String> COMPARISON_CATEGORIES = List.of("FISCAL", "ECONOMIC", "DERIVED");

    private final ObservationRepository observations;
    private final ReferenceDataService reference;

    public BudgetRecordService(ObservationRepository observations, ReferenceDataService reference) {
        this.observations = observations;
        this.reference = reference;
    }

    public List<BudgetRecordDto> search(String stateCode, String indicator, String fromYear, String toYear) {
        DatasetVersion dv = reference.currentDataset();
        reference.requireState(stateCode);
        if (indicator != null) {
            reference.requireIndicator(indicator);
        }
        if (fromYear != null && toYear != null && fromYear.compareTo(toYear) > 0) {
            throw ApiException.badRequest("from_year must not be after to_year");
        }
        return observations.search(dv.getId(), stateCode, indicator, fromYear, toYear).stream()
                .map(BudgetRecordService::toDto).toList();
    }

    public YearComparisonDto compare(String stateCode, String year, String previousYear) {
        DatasetVersion dv = reference.currentDataset();
        State state = reference.requireState(stateCode);
        String prev = previousYear != null ? previousYear : previous(year);
        if (prev.compareTo(year) >= 0) {
            throw ApiException.badRequest("previous_year must be before year");
        }
        Map<String, Observation[]> byIndicator = new LinkedHashMap<>();
        for (Observation o : observations.findForYears(dv.getId(), stateCode, List.of(prev, year), COMPARISON_CATEGORIES)) {
            Observation[] pair = byIndicator.computeIfAbsent(o.getIndicator().getCode(), k -> new Observation[2]);
            pair[o.getFiscalYear().equals(prev) ? 0 : 1] = o;
        }
        if (byIndicator.isEmpty()) {
            throw ApiException.notFound("No records for " + stateCode + " in " + prev + " or " + year);
        }
        List<ComparisonRow> rows = new ArrayList<>();
        byIndicator.forEach((code, pair) -> {
            Observation p = pair[0];
            Observation c = pair[1];
            Observation any = c != null ? c : p;
            BigDecimal change = p != null && c != null ? c.getValue().subtract(p.getValue()) : null;
            BigDecimal pct = change != null && p.getValue().signum() != 0
                    ? change.multiply(BigDecimal.valueOf(100)).divide(p.getValue().abs(), 2, RoundingMode.HALF_UP) : null;
            rows.add(new ComparisonRow(code, any.getIndicator().getName(), any.getIndicator().getUnit(),
                    p == null ? null : p.getValue(), p == null ? null : p.getDataStatus().name(),
                    c == null ? null : c.getValue(), c == null ? null : c.getDataStatus().name(), change, pct));
        });
        return new YearComparisonDto(state.getCode(), state.getName(), year, prev, rows);
    }

    static String previous(String fiscalYear) {
        int start = Integer.parseInt(fiscalYear.substring(0, 4)) - 1;
        return String.format("%d-%02d", start, (start + 1) % 100);
    }

    private static BudgetRecordDto toDto(Observation o) {
        return new BudgetRecordDto(o.getState().getCode(), o.getIndicator().getCode(), o.getIndicator().getName(),
                o.getIndicator().getUnit(), o.getFiscalYear(), o.getValue(), o.getDataStatus().name(), o.getFootnoteFlag());
    }
}
