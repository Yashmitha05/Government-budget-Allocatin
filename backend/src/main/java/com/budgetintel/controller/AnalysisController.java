package com.budgetintel.controller;

import com.budgetintel.dto.AnalysisDtos.ForecastRequest;
import com.budgetintel.dto.AnalysisDtos.ForecastResponse;
import com.budgetintel.dto.AnalysisDtos.OptimizationDto;
import com.budgetintel.dto.AnalysisDtos.OptimizationRequest;
import com.budgetintel.dto.AnalysisDtos.ScenarioDto;
import com.budgetintel.dto.AnalysisDtos.ScenarioRequest;
import com.budgetintel.dto.AnalysisDtos.ScenarioSummaryDto;
import com.budgetintel.dto.AnalysisDtos.StoredForecastDto;
import com.budgetintel.dto.AnalysisDtos.StressTestDto;
import com.budgetintel.dto.AnalysisDtos.StressTestRequest;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.security.AdminOnly;
import com.budgetintel.service.ForecastService;
import com.budgetintel.service.OptimizationService;
import com.budgetintel.service.ScenarioService;
import com.budgetintel.service.StressTestService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final ForecastService forecasts;
    private final ScenarioService scenarios;
    private final OptimizationService optimizations;
    private final StressTestService stressTests;

    public AnalysisController(ForecastService forecasts, ScenarioService scenarios, OptimizationService optimizations,
                              StressTestService stressTests) {
        this.forecasts = forecasts;
        this.scenarios = scenarios;
        this.optimizations = optimizations;
        this.stressTests = stressTests;
    }

    // ------------------------------------------------------------------------------------------------ forecasts

    @PostMapping("/forecasts")
    public ForecastResponse forecast(@Valid @RequestBody ForecastRequest req) {
        return forecasts.generate(req);
    }

    @GetMapping("/forecasts")
    public List<StoredForecastDto> listForecasts(@RequestParam(value = "state", required = false) String state,
                                                 @RequestParam(value = "indicator", required = false) String indicator,
                                                 @RequestParam(value = "kind", defaultValue = "LIVE") ForecastKind kind) {
        return forecasts.list(state, indicator, kind);
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    @PostMapping("/scenarios")
    public ResponseEntity<ScenarioDto> createScenario(@Valid @RequestBody ScenarioRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scenarios.create(req));
    }

    @GetMapping("/scenarios")
    public List<ScenarioSummaryDto> listScenarios(@RequestParam(value = "state", required = false) String state) {
        return scenarios.list(state);
    }

    @GetMapping("/scenarios/compare")
    public List<ScenarioDto> compare(@RequestParam("ids") List<Long> ids) {
        return scenarios.compare(ids);
    }

    @GetMapping("/scenarios/{id}")
    public ScenarioDto getScenario(@PathVariable("id") Long id) {
        return scenarios.get(id);
    }

    @AdminOnly
    @DeleteMapping("/scenarios/{id}")
    public ResponseEntity<Void> deleteScenario(@PathVariable("id") Long id) {
        scenarios.delete(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------------------------- optimisation

    @PostMapping("/optimizations")
    public ResponseEntity<OptimizationDto> optimize(@Valid @RequestBody OptimizationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(optimizations.run(req));
    }

    @GetMapping("/optimizations")
    public List<OptimizationDto> listOptimizations(@RequestParam(value = "state", required = false) String state) {
        return optimizations.list(state);
    }

    @GetMapping("/optimizations/{id}")
    public OptimizationDto getOptimization(@PathVariable("id") Long id) {
        return optimizations.get(id);
    }

    // -------------------------------------------------------------------------------------------- stress tests

    @PostMapping("/stress-tests")
    public ResponseEntity<StressTestDto> stressTest(@Valid @RequestBody StressTestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stressTests.run(req));
    }

    @GetMapping("/stress-tests")
    public List<StressTestDto> listStressTests(@RequestParam(value = "state", required = false) String state) {
        return stressTests.list(state);
    }

    @GetMapping("/stress-tests/{id}")
    public StressTestDto getStressTest(@PathVariable("id") Long id) {
        return stressTests.get(id);
    }
}
