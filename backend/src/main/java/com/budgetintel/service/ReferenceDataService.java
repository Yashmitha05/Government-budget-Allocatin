package com.budgetintel.service;

import com.budgetintel.dto.ReferenceDtos.DatasetDto;
import com.budgetintel.dto.ReferenceDtos.IndicatorDto;
import com.budgetintel.dto.ReferenceDtos.StateDto;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Indicator;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.DatasetVersionRepository;
import com.budgetintel.repository.IndicatorRepository;
import com.budgetintel.repository.StateRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReferenceDataService {

    private final StateRepository states;
    private final IndicatorRepository indicators;
    private final DatasetVersionRepository datasets;

    public ReferenceDataService(StateRepository states, IndicatorRepository indicators, DatasetVersionRepository datasets) {
        this.states = states;
        this.indicators = indicators;
        this.datasets = datasets;
    }

    public List<StateDto> listStates() {
        return states.findAllByOrderByNameAsc().stream()
                .map(s -> new StateDto(s.getCode(), s.getName(), s.getCategory(), s.getFormedFiscalYear(),
                        s.isHasBudgetData()))
                .toList();
    }

    public List<IndicatorDto> listIndicators() {
        return indicators.findAllByOrderByCategoryAscCodeAsc().stream()
                .map(i -> new IndicatorDto(i.getCode(), i.getName(), i.getUnit(), i.getCategory(), i.getSourceTable(),
                        i.isForecastTarget()))
                .toList();
    }

    public State requireState(String code) {
        return states.findByCode(code).orElseThrow(() -> ApiException.notFound("Unknown state code '" + code + "'"));
    }

    public Indicator requireIndicator(String code) {
        return indicators.findByCode(code).orElseThrow(() -> ApiException.notFound("Unknown indicator '" + code + "'"));
    }

    public DatasetVersion currentDataset() {
        return datasets.findByCurrentTrue().orElseThrow(() -> ApiException.conflict("NO_DATASET",
                "No dataset has been imported yet. Run POST /api/admin/datasets/import first."));
    }

    public DatasetDto currentDatasetDto() {
        DatasetVersion d = currentDataset();
        return new DatasetDto(d.getLabel(), d.getContentHash(), d.getBuiltAt(), d.getImportedAt(), d.getRecordCount(),
                d.getSourceSummary(), d.getQualityReport());
    }
}
