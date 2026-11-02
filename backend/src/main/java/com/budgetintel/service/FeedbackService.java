package com.budgetintel.service;

import com.budgetintel.client.MlDtos.MlDeviation;
import com.budgetintel.client.MlDtos.MlEvaluate;
import com.budgetintel.client.MlDtos.MlEvaluateRecord;
import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.FeedbackDtos.AccuracySummaryDto;
import com.budgetintel.dto.FeedbackDtos.DeviationDto;
import com.budgetintel.dto.FeedbackDtos.EvaluationResult;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Enums.ComparisonBasis;
import com.budgetintel.entity.Enums.DataStatus;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.entity.Forecast;
import com.budgetintel.entity.Observation;
import com.budgetintel.entity.PredictionDeviation;
import com.budgetintel.repository.DatasetVersionRepository;
import com.budgetintel.repository.ForecastRepository;
import com.budgetintel.repository.ObservationRepository;
import com.budgetintel.repository.PredictionDeviationRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closed feedback loop, database side: matches stored predictions with observations of the current dataset,
 * has the ML service compute the deviations, and stores them.
 * Basis: ACTUAL when the observation is Accounts/Actual; GOVT_RE / GOVT_BE when it is the state government's
 * own Revised / Budget Estimate (a comparison with the official estimate, not an accuracy measure).
 */
@Service
public class FeedbackService {

    private final MlServiceClient ml;
    private final ForecastRepository forecasts;
    private final PredictionDeviationRepository deviations;
    private final ObservationRepository observations;
    private final DatasetVersionRepository datasets;
    private final EntityManager em;

    public FeedbackService(MlServiceClient ml, ForecastRepository forecasts, PredictionDeviationRepository deviations,
                           ObservationRepository observations, DatasetVersionRepository datasets, EntityManager em) {
        this.ml = ml;
        this.forecasts = forecasts;
        this.deviations = deviations;
        this.observations = observations;
        this.datasets = datasets;
        this.em = em;
    }

    private static ComparisonBasis basisFor(DataStatus status) {
        return switch (status) {
            case ACCOUNTS, ACTUAL -> ComparisonBasis.ACTUAL;
            case RE -> ComparisonBasis.GOVT_RE;
            case BE -> ComparisonBasis.GOVT_BE;
            default -> null;
        };
    }

    /** Score every stored prediction that lacks an ACTUAL deviation, against the current dataset. */
    @Transactional
    public EvaluationResult evaluatePending() {
        return score(forecasts.findUnscored());
    }

    @Transactional
    public EvaluationResult score(List<Forecast> candidates) {
        Optional<DatasetVersion> current = datasets.findByCurrentTrue();
        if (current.isEmpty() || candidates.isEmpty()) {
            return new EvaluationResult(candidates.size(), 0, Map.of());
        }
        Map<String, Observation> obsIndex = new HashMap<>();
        Map<Long, List<Forecast>> byIndicator = new HashMap<>();
        candidates.forEach(f -> byIndicator.computeIfAbsent(f.getIndicator().getId(), k -> new ArrayList<>()).add(f));
        for (Long indicatorId : byIndicator.keySet()) {
            em.createQuery("""
                            select o from Observation o join fetch o.state
                            where o.datasetVersion.id = :d and o.indicator.id = :i""", Observation.class)
                    .setParameter("d", current.get().getId()).setParameter("i", indicatorId)
                    .getResultList()
                    .forEach(o -> obsIndex.put(o.getState().getId() + "|" + indicatorId + "|" + o.getFiscalYear(), o));
        }

        List<Forecast> matched = new ArrayList<>();
        List<Observation> matchedObs = new ArrayList<>();
        List<MlEvaluateRecord> records = new ArrayList<>();
        for (Forecast f : candidates) {
            Observation o = obsIndex.get(f.getState().getId() + "|" + f.getIndicator().getId() + "|" + f.getTargetFiscalYear());
            if (o == null || o.getValue().signum() <= 0 || f.getPredictedValue().signum() <= 0) {
                continue;
            }
            ComparisonBasis basis = basisFor(o.getDataStatus());
            if (basis == null || deviations.existsByForecastIdAndComparisonBasis(f.getId(), basis)) {
                continue;
            }
            matched.add(f);
            matchedObs.add(o);
            records.add(new MlEvaluateRecord(f.getId(), f.getState().getCode(), f.getIndicator().getCode(),
                    f.getTargetFiscalYear(), f.getModelVersion().getVersionNumber(), f.getPredictedValue().doubleValue(),
                    o.getValue().doubleValue()));
        }
        if (records.isEmpty()) {
            return new EvaluationResult(candidates.size(), 0, Map.of());
        }
        MlEvaluate result = ml.evaluate(records);
        Map<String, Integer> added = new TreeMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (int k = 0; k < matched.size(); k++) {
            MlDeviation dev = result.records().get(k);
            if (!matched.get(k).getId().equals(dev.predictionId())) {
                throw new IllegalStateException("ML evaluation returned records out of order");
            }
            Observation o = matchedObs.get(k);
            PredictionDeviation d = new PredictionDeviation();
            d.setForecast(matched.get(k));
            d.setObservation(o);
            d.setComparisonBasis(basisFor(o.getDataStatus()));
            d.setActualValue(o.getValue());
            d.setAbsoluteError(BigDecimal.valueOf(dev.absoluteError()));
            d.setPercentageError(BigDecimal.valueOf(dev.percentageError()).setScale(4, RoundingMode.HALF_UP));
            d.setEvaluatedAt(now);
            deviations.save(d);
            added.merge(d.getComparisonBasis().name(), 1, Integer::sum);
        }
        deviations.flush();
        return new EvaluationResult(candidates.size(), matched.size(), added);
    }

    @Transactional(readOnly = true)
    public List<DeviationDto> search(String stateCode, String indicator, Integer version, ComparisonBasis basis,
                                     ForecastKind kind) {
        return deviations.search(stateCode, indicator, version, basis, kind).stream().map(d -> {
            Forecast f = d.getForecast();
            return new DeviationDto(f.getId(), f.getState().getCode(), f.getIndicator().getCode(), f.getOriginFiscalYear(),
                    f.getTargetFiscalYear(), f.getHorizon(), f.getKind().name(), f.getModelVersion().getVersionNumber(),
                    f.getModelVersion().getModelType(), d.getComparisonBasis().name(), f.getPredictedValue(),
                    d.getActualValue(), d.getAbsoluteError(), d.getPercentageError(), d.getEvaluatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<AccuracySummaryDto> summary() {
        return deviations.summary().stream().map(r -> new AccuracySummaryDto(
                (String) r[0], (Integer) r[1], (String) r[2], ((ComparisonBasis) r[3]).name(), ((ForecastKind) r[4]).name(),
                ((Number) r[5]).intValue(), ((Number) r[6]).longValue(), round(r[7]), round(r[8]), round(r[9]))).toList();
    }

    private static BigDecimal round(Object v) {
        return v == null ? null : new BigDecimal(v.toString()).setScale(4, RoundingMode.HALF_UP);
    }
}
