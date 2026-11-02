package com.budgetintel.service;

import com.budgetintel.client.MlDtos.MlBacktest;
import com.budgetintel.client.MlDtos.MlBacktestRow;
import com.budgetintel.client.MlDtos.MlDeviationRecord;
import com.budgetintel.client.MlDtos.MlModelVersion;
import com.budgetintel.client.MlServiceClient;
import com.budgetintel.client.MlServiceException;
import com.budgetintel.dto.FeedbackDtos.ModelVersionDto;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.entity.Forecast;
import com.budgetintel.entity.Indicator;
import com.budgetintel.entity.ModelVersion;
import com.budgetintel.entity.PredictionDeviation;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.ForecastRepository;
import com.budgetintel.repository.IndicatorRepository;
import com.budgetintel.repository.ModelVersionRepository;
import com.budgetintel.repository.PredictionDeviationRepository;
import com.budgetintel.repository.StateRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mirrors the ML model registry in PostgreSQL and drives retraining and calibration. */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);
    static final int EVALUATION_YEARS = 2;   // most recent target years held out when calibrating

    private final MlServiceClient ml;
    private final ModelVersionRepository versions;
    private final IndicatorRepository indicators;
    private final StateRepository states;
    private final ForecastRepository forecasts;
    private final PredictionDeviationRepository deviations;
    private final FeedbackService feedback;

    public ModelService(MlServiceClient ml, ModelVersionRepository versions, IndicatorRepository indicators,
                        StateRepository states, ForecastRepository forecasts, PredictionDeviationRepository deviations,
                        FeedbackService feedback) {
        this.ml = ml;
        this.versions = versions;
        this.indicators = indicators;
        this.states = states;
        this.forecasts = forecasts;
        this.deviations = deviations;
        this.feedback = feedback;
    }

    /** Upsert every ML model version; returns the number of versions known. */
    @Transactional
    public int sync() {
        List<MlModelVersion> remote = ml.models().stream()
                .sorted(Comparator.comparing(MlModelVersion::target).thenComparingInt(MlModelVersion::version))
                .toList();
        Map<String, ModelVersion> byKey = new HashMap<>();
        for (MlModelVersion r : remote) {
            Indicator ind = indicators.findByCode(r.target())
                    .orElseThrow(() -> ApiException.conflict("UNKNOWN_INDICATOR", "Model target not in catalogue: " + r.target()));
            ModelVersion mv = versions.findVersion(r.target(), r.version()).orElseGet(ModelVersion::new);
            mv.setTargetIndicator(ind);
            mv.setVersionNumber(r.version());
            mv.setModelType(r.modelType());
            mv.setDatasetLabel(r.datasetLabel());
            mv.setDatasetHash(r.datasetHash());
            mv.setTrainedAt(OffsetDateTime.parse(r.trainedAt()));
            mv.setTrainYearFrom(r.trainYearFrom());
            mv.setTrainYearTo(r.trainYearTo());
            mv.setMetrics(metrics(r));
            mv.setConfig(config(r));
            mv.setCalibrated(r.calibration() != null);
            mv.setArtifactPath(r.artifactPath());
            mv.setActive(false);
            mv.setParentVersion(r.parentVersion() == null ? null : byKey.get(r.target() + "#" + r.parentVersion()));
            byKey.put(r.target() + "#" + r.version(), versions.save(mv));
        }
        versions.flush();   // all inactive first, so the partial unique index on is_active holds
        for (MlModelVersion r : remote) {
            if (r.isActive()) {
                byKey.get(r.target() + "#" + r.version()).setActive(true);
            }
        }
        versions.flush();
        return remote.size();
    }

    private static Map<String, Object> metrics(MlModelVersion r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holdout_h1", r.metrics());
        m.put("holdout_by_model", r.holdoutMetrics());
        m.put("selection_mape_h1", r.selectionMapeH1());
        m.put("interval_coverage_holdout", r.intervalCoverageHoldout());
        if (r.calibrationEvaluation() != null) {
            m.put("calibration_evaluation", r.calibrationEvaluation());
        }
        return m;
    }

    private static Map<String, Object> config(MlModelVersion r) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("features", r.features());
        c.put("params", r.params());
        c.put("interval_quantiles", r.intervalQuantiles());
        if (r.calibration() != null) {
            c.put("calibration", r.calibration());
        }
        return c;
    }

    /**
     * Store the out-of-time backtest predictions behind each uncalibrated version trained on the given dataset
     * as BACKTEST forecasts. They are the earliest real prediction-vs-actual records of the feedback loop.
     */
    @Transactional
    public int importBacktests(String datasetHash) {
        Map<String, State> stateByCode = new HashMap<>();
        states.findAll().forEach(s -> stateByCode.put(s.getCode(), s));
        int added = 0;
        for (ModelVersion mv : versions.list(null)) {
            if (mv.isCalibrated() || mv.getParentVersion() != null || !datasetHash.equals(mv.getDatasetHash())) {
                continue;   // calibrated/retrained children share their root's backtest
            }
            MlBacktest bt;
            try {
                bt = ml.backtest(mv.getTargetIndicator().getCode(), mv.getModelType(), null);
            } catch (MlServiceException e) {
                log.warn("No backtest for {} {}: {}", mv.getTargetIndicator().getCode(), mv.getModelType(), e.getMessage());
                continue;
            }
            for (MlBacktestRow row : bt.rows()) {
                State st = stateByCode.get(row.stateCode());
                if (st == null || forecasts.findKey(mv.getId(), st.getId(), mv.getTargetIndicator().getId(),
                        row.origin(), row.targetYear()).isPresent()) {
                    continue;
                }
                Forecast f = new Forecast();
                f.setModelVersion(mv);
                f.setState(st);
                f.setIndicator(mv.getTargetIndicator());
                f.setOriginFiscalYear(row.origin());
                f.setTargetFiscalYear(row.targetYear());
                f.setHorizon((short) row.horizon());
                f.setPredictedValue(BigDecimal.valueOf(row.predicted()));
                f.setKind(ForecastKind.BACKTEST);
                f.setCreatedAt(OffsetDateTime.now());
                forecasts.save(f);
                added++;
            }
        }
        forecasts.flush();
        return added;
    }

    @Transactional(readOnly = true)
    public List<ModelVersionDto> list(String target) {
        return versions.list(target).stream().map(ModelService::toDto).toList();
    }

    static ModelVersionDto toDto(ModelVersion m) {
        return new ModelVersionDto(m.getTargetIndicator().getCode(), m.getVersionNumber(), m.getModelType(), m.isActive(),
                m.isCalibrated(), m.getTrainedAt(), m.getDatasetLabel(), m.getTrainYearFrom(), m.getTrainYearTo(),
                m.getParentVersion() == null ? null : m.getParentVersion().getVersionNumber(), m.getMetrics(),
                m.getConfig());
    }

    ModelVersion requireVersion(String target, int version) {
        return versions.findVersion(target, version).orElseGet(() -> {
            sync();
            return versions.findVersion(target, version).orElseThrow(() ->
                    ApiException.notFound("Model " + target + " v" + version + " is not registered"));
        });
    }

    @Transactional
    public ModelVersionDto retrain(String target) {
        requireTarget(target);
        MlModelVersion v = ml.retrain(target, true);
        sync();
        feedback.evaluatePending();
        return toDto(requireVersion(target, v.version()));
    }

    /**
     * Feedback-loop calibration from stored one-year-ahead ACTUAL deviations of the active model type
     * (uncalibrated versions only). The latest {@value #EVALUATION_YEARS} target years are held out to
     * decide whether the calibrated version is activated.
     */
    @Transactional
    public ModelVersionDto calibrate(String target) {
        requireTarget(target);
        ModelVersion active = versions.findActive(target)
                .orElseThrow(() -> ApiException.notFound("No active model for " + target));
        List<PredictionDeviation> devs = deviations.oneStepActuals(target, active.getModelType()).stream()
                .filter(d -> !d.getForecast().getModelVersion().isCalibrated())
                .toList();
        TreeSet<String> years = new TreeSet<>();
        devs.forEach(d -> years.add(d.getForecast().getTargetFiscalYear()));
        if (years.size() <= EVALUATION_YEARS) {
            throw ApiException.conflict("INSUFFICIENT_HISTORY",
                    "Calibration needs deviation records for more than " + EVALUATION_YEARS + " target years");
        }
        String firstEval = years.descendingSet().stream().skip(EVALUATION_YEARS - 1).findFirst().orElseThrow();
        List<MlDeviationRecord> cal = devs.stream().filter(d -> d.getForecast().getTargetFiscalYear().compareTo(firstEval) < 0)
                .map(ModelService::record).toList();
        List<MlDeviationRecord> eval = devs.stream().filter(d -> d.getForecast().getTargetFiscalYear().compareTo(firstEval) >= 0)
                .map(ModelService::record).toList();
        MlModelVersion v = ml.calibrate(target, cal, eval);
        sync();
        return toDto(requireVersion(target, v.version()));
    }

    private static MlDeviationRecord record(PredictionDeviation d) {
        Forecast f = d.getForecast();
        return new MlDeviationRecord(f.getState().getCode(), f.getTargetFiscalYear(), f.getPredictedValue().doubleValue(),
                d.getActualValue().doubleValue());
    }

    private void requireTarget(String target) {
        Indicator i = indicators.findByCode(target).orElseThrow(() -> ApiException.notFound("Unknown indicator '" + target + "'"));
        if (!i.isForecastTarget()) {
            throw ApiException.badRequest("'" + target + "' is not a forecast target");
        }
    }
}
