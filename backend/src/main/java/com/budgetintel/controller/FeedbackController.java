package com.budgetintel.controller;

import com.budgetintel.dto.FeedbackDtos.AccuracySummaryDto;
import com.budgetintel.dto.FeedbackDtos.DeviationDto;
import com.budgetintel.dto.FeedbackDtos.EvaluationResult;
import com.budgetintel.dto.FeedbackDtos.ModelVersionDto;
import com.budgetintel.entity.Enums.ComparisonBasis;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.service.FeedbackService;
import com.budgetintel.service.ModelService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final FeedbackService feedback;
    private final ModelService models;

    public FeedbackController(FeedbackService feedback, ModelService models) {
        this.feedback = feedback;
        this.models = models;
    }

    @GetMapping("/feedback/deviations")
    public List<DeviationDto> deviations(@RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "indicator", required = false) String indicator,
                                         @RequestParam(value = "model_version", required = false) Integer modelVersion,
                                         @RequestParam(value = "basis", required = false) ComparisonBasis basis,
                                         @RequestParam(value = "kind", required = false) ForecastKind kind) {
        return feedback.search(state, indicator, modelVersion, basis, kind);
    }

    @GetMapping("/feedback/summary")
    public List<AccuracySummaryDto> summary() {
        return feedback.summary();
    }

    /** Score every stored prediction that now has a matching observation in the current dataset. */
    @PostMapping("/feedback/evaluate")
    public EvaluationResult evaluate() {
        return feedback.evaluatePending();
    }

    @GetMapping("/models")
    public List<ModelVersionDto> models(@RequestParam(value = "indicator", required = false) String indicator) {
        return models.list(indicator);
    }

    @PostMapping("/models/sync")
    public List<ModelVersionDto> sync() {
        models.sync();
        return models.list(null);
    }

    @PostMapping("/models/{indicator}/retrain")
    public ModelVersionDto retrain(@PathVariable("indicator") String indicator) {
        return models.retrain(indicator);
    }

    @PostMapping("/models/{indicator}/calibrate")
    public ModelVersionDto calibrate(@PathVariable("indicator") String indicator) {
        return models.calibrate(indicator);
    }
}
