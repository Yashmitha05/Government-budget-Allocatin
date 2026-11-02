package com.budgetintel.service;

import com.budgetintel.client.MlDtos.MlDataset;
import com.budgetintel.client.MlDtos.MlIndicator;
import com.budgetintel.client.MlDtos.MlObservation;
import com.budgetintel.client.MlDtos.MlObservations;
import com.budgetintel.client.MlDtos.MlState;
import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.FeedbackDtos.EvaluationResult;
import com.budgetintel.dto.ReferenceDtos.DatasetImportResult;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Indicator;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.DatasetVersionRepository;
import com.budgetintel.repository.IndicatorRepository;
import com.budgetintel.repository.ObservationRepository;
import com.budgetintel.repository.StateRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the ML service's current processed dataset into PostgreSQL, then synchronises model versions,
 * loads out-of-time backtest predictions and scores every stored prediction that now has an actual.
 * Idempotent: a dataset whose content hash is already present is not loaded again.
 */
@Service
public class DatasetImportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetImportService.class);

    private final MlServiceClient ml;
    private final DatasetVersionRepository datasets;
    private final StateRepository states;
    private final IndicatorRepository indicators;
    private final ObservationRepository observations;
    private final JdbcTemplate jdbc;
    private final ModelService models;
    private final FeedbackService feedback;

    public DatasetImportService(MlServiceClient ml, DatasetVersionRepository datasets, StateRepository states,
                                IndicatorRepository indicators, ObservationRepository observations, JdbcTemplate jdbc,
                                ModelService models, FeedbackService feedback) {
        this.ml = ml;
        this.datasets = datasets;
        this.states = states;
        this.indicators = indicators;
        this.observations = observations;
        this.jdbc = jdbc;
        this.models = models;
        this.feedback = feedback;
    }

    @Transactional
    public DatasetImportResult importCurrent() {
        MlDataset meta = ml.dataset();
        Map<String, State> stateByCode = upsertStates(ml.states());
        Map<String, Indicator> indicatorByCode = upsertIndicators(ml.indicators());

        Optional<DatasetVersion> existing = datasets.findByContentHash(meta.contentHash());
        DatasetVersion dv;
        boolean created = existing.isEmpty();
        if (created) {
            MlObservations obs = ml.observations();
            if (!meta.label().equals(obs.datasetLabel())) {
                throw new ApiException(HttpStatus.CONFLICT, "DATASET_CHANGED",
                        "The ML dataset changed during import; retry", List.of());
            }
            datasets.clearCurrent();
            dv = new DatasetVersion();
            dv.setLabel(meta.label());
            dv.setContentHash(meta.contentHash());
            dv.setBuiltAt(meta.builtAt());
            dv.setImportedAt(OffsetDateTime.now());
            dv.setRecordCount(obs.count());
            dv.setSourceSummary(String.join("\n", meta.sources()));
            dv.setQualityReport(quality(meta));
            dv.setCurrent(true);
            dv = datasets.saveAndFlush(dv);
            insertObservations(dv.getId(), obs.observations(), stateByCode, indicatorByCode);
            log.info("Imported dataset {} with {} observations", dv.getLabel(), obs.count());
        } else {
            dv = existing.get();
            if (!dv.isCurrent()) {
                datasets.clearCurrent();
                datasets.flush();
                dv.setCurrent(true);
            }
        }
        int modelCount = models.sync();
        int backtests = models.importBacktests(meta.contentHash());
        EvaluationResult eval = feedback.evaluatePending();
        return new DatasetImportResult(dv.getLabel(), created, observations.countByDatasetVersionId(dv.getId()),
                stateByCode.size(), indicatorByCode.size(), modelCount, backtests, eval.deviationsAdded());
    }

    private static Map<String, Object> quality(MlDataset m) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("coverage", m.coverage());
        q.put("aggregate_check", m.aggregateCheck());
        q.put("outlier_flag_count", m.outlierFlagCount());
        q.put("population_sum_vs_wdi", m.populationSumVsWdi());
        q.put("structural_breaks", m.structuralBreaks());
        q.put("source_files", m.sourceFiles());
        return q;
    }

    private Map<String, State> upsertStates(List<MlState> incoming) {
        Map<String, State> out = new HashMap<>();
        for (MlState s : incoming) {
            State st = states.findByCode(s.code()).orElseGet(State::new);
            st.setCode(s.code());
            st.setName(s.name());
            st.setCategory(s.category());
            st.setFormedFiscalYear(s.formedFiscalYear());
            st.setHasBudgetData(s.hasBudgetData());
            out.put(s.code(), states.save(st));
        }
        states.flush();
        return out;
    }

    private Map<String, Indicator> upsertIndicators(List<MlIndicator> incoming) {
        Map<String, Indicator> out = new HashMap<>();
        for (MlIndicator i : incoming) {
            Indicator ind = indicators.findByCode(i.code()).orElseGet(Indicator::new);
            ind.setCode(i.code());
            ind.setName(i.name());
            ind.setUnit(i.unit());
            ind.setCategory(i.category());
            ind.setSourceTable(i.sourceTable());
            ind.setForecastTarget(i.isForecastTarget());
            out.put(i.code(), indicators.save(ind));
        }
        indicators.flush();
        return out;
    }

    private void insertObservations(Long datasetId, List<MlObservation> rows, Map<String, State> stateByCode,
                                    Map<String, Indicator> indicatorByCode) {
        // The ML aggregates ('ALL' = all states, 'IN' = India national series) are reference rows too.
        List<Object[]> batch = rows.stream()
                .filter(o -> stateByCode.containsKey(o.stateCode()) || "IN".equals(o.stateCode()))
                .map(o -> new Object[]{datasetId, stateId(o.stateCode(), stateByCode), indicatorId(o.indicator(), indicatorByCode),
                        o.fiscalYear(), BigDecimal.valueOf(o.value()), o.dataStatus(), o.footnoteFlag()})
                .toList();
        jdbc.batchUpdate("""
                insert into observation (dataset_version_id, state_id, indicator_id, fiscal_year, value, data_status, footnote_flag)
                values (?, ?, ?, ?, ?, ?, ?)""", batch);
    }

    private Long stateId(String code, Map<String, State> byCode) {
        State s = byCode.get(code);
        if (s == null) {    // national WDI series ('IN') is stored against a dedicated aggregate row
            s = byCode.computeIfAbsent(code, c -> {
                State st = states.findByCode(c).orElseGet(State::new);
                st.setCode(c);
                st.setName("India (national series)");
                st.setCategory("AGGREGATE");
                return states.saveAndFlush(st);
            });
        }
        return s.getId();
    }

    private static Long indicatorId(String code, Map<String, Indicator> byCode) {
        Indicator i = byCode.get(code);
        if (i == null) {
            throw new ApiException(HttpStatus.CONFLICT, "UNKNOWN_INDICATOR",
                    "Observation references an indicator missing from the catalogue: " + code, List.of());
        }
        return i.getId();
    }
}
