package com.budgetintel.service;

import static com.budgetintel.service.Num.dec;
import static com.budgetintel.service.Num.map;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.AnalysisDtos.ScenarioDto;
import com.budgetintel.dto.AnalysisDtos.ScenarioRequest;
import com.budgetintel.dto.AnalysisDtos.ScenarioSummaryDto;
import com.budgetintel.entity.BudgetScenario;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Enums.ScenarioType;
import com.budgetintel.entity.ScenarioAllocation;
import com.budgetintel.entity.ScenarioOutcome;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.BudgetScenarioRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Budget allocation scenarios: computed by the ML service, stored with their allocations and indicator effects. */
@Service
public class ScenarioService {

    private static final int MAX_COMPARE = 6;

    private final MlServiceClient ml;
    private final ReferenceDataService reference;
    private final BudgetScenarioRepository scenarios;

    public ScenarioService(MlServiceClient ml, ReferenceDataService reference, BudgetScenarioRepository scenarios) {
        this.ml = ml;
        this.reference = reference;
        this.scenarios = scenarios;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ScenarioDto create(ScenarioRequest req) {
        DatasetVersion dataset = reference.currentDataset();
        State state = reference.requireState(req.stateCode());
        boolean custom = "CUSTOM".equals(req.scenarioType());
        boolean hasAlloc = req.allocations() != null && !req.allocations().isEmpty();
        boolean hasAdj = req.adjustmentsPct() != null && !req.adjustmentsPct().isEmpty();
        if (custom && hasAlloc == hasAdj) {
            throw ApiException.badRequest("A custom scenario needs either allocations or percentage adjustments (not both)");
        }
        if (!custom && (hasAlloc || hasAdj)) {
            throw ApiException.badRequest("Allocations and adjustments are only used with the CUSTOM scenario type");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("state_code", state.getCode());
        body.put("fiscal_year", req.fiscalYear());
        body.put("scenario_type", req.scenarioType());
        body.put("allocations", hasAlloc ? req.allocations() : null);
        body.put("adjustments_pct", hasAdj ? req.adjustmentsPct() : null);
        Map<String, Object> r = ml.scenario(body);

        Map<String, Object> fx = map(r.get("fiscal_effects"));
        Map<String, Object> base = map(fx.get("baseline"));
        Map<String, Object> scen = map(fx.get("scenario"));
        BudgetScenario s = new BudgetScenario();
        s.setName(req.name().trim());
        s.setScenarioType(ScenarioType.valueOf(req.scenarioType()));
        s.setState(state);
        s.setFiscalYear((String) r.get("fiscal_year"));
        s.setBaselineDataStatus((String) r.get("baseline_data_status"));
        s.setDescription((String) r.get("description"));
        s.setBaselineGfd(dec(base.get("gross_fiscal_deficit"), 2));
        s.setScenarioGfd(dec(scen.get("gross_fiscal_deficit"), 2));
        s.setBaselineGfdPctGsdp(dec(base.get("gfd_pct_gsdp"), 4));
        s.setScenarioGfdPctGsdp(dec(scen.get("gfd_pct_gsdp"), 4));
        s.setBreachesFrbmLimit(Boolean.TRUE.equals(scen.get("breaches_frbm_gfd_limit")));
        s.setDatasetVersion(dataset);
        s.setNotes(req.notes());
        s.setResultSnapshot(r);
        s.setCreatedAt(OffsetDateTime.now());
        for (Object o : (List<Object>) r.get("sectors")) {
            Map<String, Object> sec = map(o);
            s.addAllocation(new ScenarioAllocation((String) sec.get("sector"), dec(sec.get("baseline"), 2),
                    dec(sec.get("scenario"), 2), dec(sec.get("change_pct"), 4)));
        }
        for (Object o : (List<Object>) r.get("indicator_effects")) {
            Map<String, Object> e = map(o);
            s.addOutcome(new ScenarioOutcome(reference.requireIndicator((String) e.get("indicator")),
                    (String) e.get("driver_sector"), dec(e.get("current_value"), 4), dec(e.get("predicted_value"), 4),
                    dec(e.get("lower"), 4), dec(e.get("upper"), 4), Boolean.TRUE.equals(e.get("supported")),
                    truncate((String) e.get("method_note"), 400)));
        }
        return toDto(scenarios.save(s));
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }

    @Transactional(readOnly = true)
    public List<ScenarioSummaryDto> list(String stateCode) {
        return scenarios.search(stateCode).stream().map(s -> new ScenarioSummaryDto(s.getId(), s.getName(),
                s.getScenarioType().name(), s.getState().getCode(), s.getFiscalYear(), s.getBaselineDataStatus(),
                s.getBaselineGfdPctGsdp(), s.getScenarioGfdPctGsdp(), s.isBreachesFrbmLimit(), s.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public ScenarioDto get(Long id) {
        return toDto(require(id));
    }

    @Transactional(readOnly = true)
    public List<ScenarioDto> compare(List<Long> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > MAX_COMPARE) {
            throw ApiException.badRequest("Select between 2 and " + MAX_COMPARE + " scenarios to compare");
        }
        return ids.stream().distinct().map(this::require).map(this::toDto).toList();
    }

    @Transactional
    public void delete(Long id) {
        scenarios.delete(require(id));
    }

    BudgetScenario require(Long id) {
        return scenarios.findById(id).orElseThrow(() -> ApiException.notFound("Scenario " + id + " not found"));
    }

    private ScenarioDto toDto(BudgetScenario s) {
        return new ScenarioDto(s.getId(), s.getName(), s.getScenarioType().name(), s.getState().getCode(),
                s.getFiscalYear(), s.getNotes(), s.getDatasetVersion().getLabel(), s.getCreatedAt(), s.getResultSnapshot());
    }
}
