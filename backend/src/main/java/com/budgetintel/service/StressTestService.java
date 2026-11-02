package com.budgetintel.service;

import static com.budgetintel.service.Num.dec;
import static com.budgetintel.service.Num.map;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.AnalysisDtos.StressTestDto;
import com.budgetintel.dto.AnalysisDtos.StressTestRequest;
import com.budgetintel.entity.BudgetScenario;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Enums.OptimizationStatus;
import com.budgetintel.entity.Enums.StressSourceType;
import com.budgetintel.entity.OptimizationRun;
import com.budgetintel.entity.State;
import com.budgetintel.entity.StressTest;
import com.budgetintel.entity.StressTestResult;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.StressTestRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stress tests of the current budget, a saved scenario or an optimised allocation. */
@Service
public class StressTestService {

    private static final List<String> OVERRIDE_KEYS =
            List.of("revenue_shock_pct", "inflation_pct", "population_excess_growth_pct", "gsdp_shortfall_pct");

    private final MlServiceClient ml;
    private final ReferenceDataService reference;
    private final ScenarioService scenarios;
    private final OptimizationService optimizations;
    private final StressTestRepository tests;

    public StressTestService(MlServiceClient ml, ReferenceDataService reference, ScenarioService scenarios,
                             OptimizationService optimizations, StressTestRepository tests) {
        this.ml = ml;
        this.reference = reference;
        this.scenarios = scenarios;
        this.optimizations = optimizations;
        this.tests = tests;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public StressTestDto run(StressTestRequest req) {
        DatasetVersion dataset = reference.currentDataset();
        State state = reference.requireState(req.stateCode());
        StressSourceType source = req.sourceType() == null ? StressSourceType.CURRENT : StressSourceType.valueOf(req.sourceType());
        String fiscalYear = req.fiscalYear();
        Map<String, BigDecimal> allocation = null;

        if (source != StressSourceType.CURRENT) {
            if (req.sourceId() == null) {
                throw ApiException.badRequest("source_id is required when source_type is " + source);
            }
            if (source == StressSourceType.SCENARIO) {
                BudgetScenario s = scenarios.require(req.sourceId());
                checkSource(state, fiscalYear, s.getState(), s.getFiscalYear(), "Scenario");
                fiscalYear = s.getFiscalYear();
                allocation = new LinkedHashMap<>();
                for (var a : s.getAllocations()) {
                    allocation.put(a.getSector(), a.getScenarioAmount());
                }
            } else {
                OptimizationRun r = optimizations.require(req.sourceId());
                if (r.getStatus() != OptimizationStatus.OPTIMAL) {
                    throw ApiException.badRequest("Optimization run " + r.getId() + " has no feasible allocation to test");
                }
                checkSource(state, fiscalYear, r.getState(), r.getFiscalYear(), "Optimization run");
                fiscalYear = r.getFiscalYear();
                allocation = new LinkedHashMap<>();
                for (var a : r.getAllocations()) {
                    allocation.put(a.getSector(), a.getRecommendedAmount());
                }
            }
        } else if (req.sourceId() != null) {
            throw ApiException.badRequest("source_id must be empty when source_type is CURRENT");
        }
        if (req.overrides() != null) {
            for (String k : req.overrides().keySet()) {
                if (!OVERRIDE_KEYS.contains(k)) {
                    throw ApiException.badRequest("Unknown shock override '" + k + "'", "valid: " + OVERRIDE_KEYS);
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("state_code", state.getCode());
        body.put("fiscal_year", fiscalYear);
        body.put("allocation", allocation);
        body.put("overrides", req.overrides());
        body.put("scenarios", req.scenarios() == null || req.scenarios().isEmpty() ? null : req.scenarios());
        Map<String, Object> r = ml.stressTest(body);

        StressTest t = new StressTest();
        t.setState(state);
        t.setFiscalYear((String) r.get("fiscal_year"));
        t.setSourceType(source);
        t.setSourceId(req.sourceId());
        t.setShocks(map(r.get("calibration")));
        t.setDatasetVersion(dataset);
        t.setResultSnapshot(r);
        t.setCreatedAt(OffsetDateTime.now());
        for (Object o : (List<Object>) r.get("results")) {
            Map<String, Object> x = map(o);
            t.addResult(new StressTestResult((String) x.get("scenario"), (String) x.get("metric"), dec(x.get("normal")),
                    dec(x.get("stressed")), Boolean.TRUE.equals(x.get("breaches_limit"))));
        }
        return toDto(tests.save(t));
    }

    private static void checkSource(State requested, String fiscalYear, State sourceState, String sourceYear, String what) {
        if (!requested.getId().equals(sourceState.getId())) {
            throw ApiException.badRequest(what + " belongs to " + sourceState.getCode() + ", not " + requested.getCode());
        }
        if (fiscalYear != null && !fiscalYear.equals(sourceYear)) {
            throw ApiException.badRequest(what + " is for " + sourceYear + ", not " + fiscalYear);
        }
    }

    @Transactional(readOnly = true)
    public List<StressTestDto> list(String stateCode) {
        return tests.search(stateCode).stream().map(StressTestService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public StressTestDto get(Long id) {
        return toDto(tests.findById(id).orElseThrow(() -> ApiException.notFound("Stress test " + id + " not found")));
    }

    private static StressTestDto toDto(StressTest t) {
        return new StressTestDto(t.getId(), t.getState().getCode(), t.getFiscalYear(), t.getSourceType().name(),
                t.getSourceId(), t.getDatasetVersion().getLabel(), t.getCreatedAt(), t.getResultSnapshot());
    }
}
