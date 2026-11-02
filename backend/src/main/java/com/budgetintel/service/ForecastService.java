package com.budgetintel.service;

import com.budgetintel.client.MlDtos.MlForecast;
import com.budgetintel.client.MlDtos.MlPoint;
import com.budgetintel.client.MlDtos.MlPrediction;
import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.AnalysisDtos.ForecastRequest;
import com.budgetintel.dto.AnalysisDtos.ForecastResponse;
import com.budgetintel.dto.AnalysisDtos.ModelRefDto;
import com.budgetintel.dto.AnalysisDtos.PredictionDto;
import com.budgetintel.dto.AnalysisDtos.SeriesPoint;
import com.budgetintel.dto.AnalysisDtos.StoredForecastDto;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.entity.Forecast;
import com.budgetintel.entity.Indicator;
import com.budgetintel.entity.ModelVersion;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.ForecastRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forecasts via the ML service. Every prediction is stored (kind LIVE) under the model version that produced it,
 * so it can be scored when the actual is published. Repeating a request with the same model version and origin
 * returns the same Prediction IDs instead of duplicating rows.
 */
@Service
public class ForecastService {

    private final MlServiceClient ml;
    private final ReferenceDataService reference;
    private final ModelService models;
    private final ForecastRepository forecasts;
    private final FeedbackService feedback;

    public ForecastService(MlServiceClient ml, ReferenceDataService reference, ModelService models,
                           ForecastRepository forecasts, FeedbackService feedback) {
        this.ml = ml;
        this.reference = reference;
        this.models = models;
        this.forecasts = forecasts;
        this.feedback = feedback;
    }

    @Transactional
    public ForecastResponse generate(ForecastRequest req) {
        reference.currentDataset();
        State state = reference.requireState(req.stateCode());
        Indicator indicator = reference.requireIndicator(req.indicator());
        if (!indicator.isForecastTarget()) {
            throw ApiException.badRequest("'" + req.indicator() + "' is not a forecast target");
        }
        int horizon = req.horizon() == null ? 3 : req.horizon();
        MlForecast f = ml.forecast(state.getCode(), indicator.getCode(), horizon);
        ModelVersion mv = models.requireVersion(indicator.getCode(), f.model().version());

        List<Forecast> stored = new ArrayList<>();
        List<PredictionDto> preds = new ArrayList<>();
        for (MlPrediction p : f.predictions()) {
            Forecast row = forecasts.findKey(mv.getId(), state.getId(), indicator.getId(), f.originFiscalYear(), p.fiscalYear())
                    .orElseGet(Forecast::new);
            if (row.getId() == null) {
                row.setModelVersion(mv);
                row.setState(state);
                row.setIndicator(indicator);
                row.setOriginFiscalYear(f.originFiscalYear());
                row.setTargetFiscalYear(p.fiscalYear());
                row.setHorizon((short) p.horizon());
                row.setPredictedValue(BigDecimal.valueOf(p.value()));
                row.setLowerBound(p.lower() == null ? null : BigDecimal.valueOf(p.lower()));
                row.setUpperBound(p.upper() == null ? null : BigDecimal.valueOf(p.upper()));
                row.setKind(ForecastKind.LIVE);
                row.setCreatedAt(OffsetDateTime.now());
                row = forecasts.save(row);
            }
            stored.add(row);
            preds.add(new PredictionDto(row.getId(), p.fiscalYear(), p.horizon(), p.value(), p.lower(), p.upper()));
        }
        forecasts.flush();
        feedback.score(stored);   // compares with the state's RE/BE now, and with actuals once published

        return new ForecastResponse(f.stateCode(), indicator.getCode(), f.unit(), f.originFiscalYear(),
                new ModelRefDto(indicator.getCode(), f.model().version(), f.model().modelType(), f.model().calibrated()),
                points(f.history()), points(f.governmentEstimates()), preds, f.backtestMetrics(), f.intervalNote());
    }

    private static List<SeriesPoint> points(List<MlPoint> in) {
        return in.stream().map(p -> new SeriesPoint(p.fiscalYear(), p.value(), p.dataStatus())).toList();
    }

    @Transactional(readOnly = true)
    public List<StoredForecastDto> list(String stateCode, String indicator, ForecastKind kind) {
        return forecasts.search(stateCode, indicator, kind).stream().map(f -> new StoredForecastDto(
                f.getId(), f.getState().getCode(), f.getIndicator().getCode(), f.getOriginFiscalYear(),
                f.getTargetFiscalYear(), f.getHorizon(), f.getPredictedValue(), f.getLowerBound(), f.getUpperBound(),
                f.getKind().name(), f.getModelVersion().getVersionNumber(), f.getModelVersion().getModelType(),
                f.getCreatedAt())).toList();
    }
}
