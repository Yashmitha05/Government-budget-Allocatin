package com.budgetintel.service;

import static com.budgetintel.service.Num.dec;
import static com.budgetintel.service.Num.map;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.client.MlServiceException;
import com.budgetintel.dto.AnalysisDtos.OptimizationDto;
import com.budgetintel.dto.AnalysisDtos.OptimizationRequest;
import com.budgetintel.dto.AnalysisDtos.SectorBound;
import com.budgetintel.entity.DatasetVersion;
import com.budgetintel.entity.Enums.OptimizationObjective;
import com.budgetintel.entity.Enums.OptimizationStatus;
import com.budgetintel.entity.OptimizationAllocation;
import com.budgetintel.entity.OptimizationRun;
import com.budgetintel.entity.State;
import com.budgetintel.exception.ApiException;
import com.budgetintel.repository.OptimizationRunRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constrained allocation via the ML service. Infeasible constraint sets are a normal outcome, not a failure:
 * the run is stored with status INFEASIBLE and the reasons, and returned with HTTP 200.
 */
@Service
public class OptimizationService {

    private final MlServiceClient ml;
    private final ReferenceDataService reference;
    private final OptimizationRunRepository runs;

    public OptimizationService(MlServiceClient ml, ReferenceDataService reference, OptimizationRunRepository runs) {
        this.ml = ml;
        this.reference = reference;
        this.runs = runs;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public OptimizationDto run(OptimizationRequest req) {
        DatasetVersion dataset = reference.currentDataset();
        State state = reference.requireState(req.stateCode());
        String objective = req.objective() == null ? "MIN_ADJUSTMENT" : req.objective();

        Map<String, Object> body = new HashMap<>();
        body.put("state_code", state.getCode());
        body.put("fiscal_year", req.fiscalYear());
        body.put("objective", objective);
        body.put("total_budget", req.totalBudget());
        body.put("social_floor", req.socialFloor() == null ? "NONE" : req.socialFloor());
        body.put("enforce_deficit_limit", req.enforceDeficitLimit() == null || req.enforceDeficitLimit());
        if (req.bounds() != null && !req.bounds().isEmpty()) {
            Map<String, Object> b = new HashMap<>();
            req.bounds().forEach((sector, sb) -> b.put(sector, boundMap(sb)));
            body.put("bounds", b);
        }

        OptimizationRun run = new OptimizationRun();
        run.setState(state);
        run.setObjective(OptimizationObjective.valueOf(objective));
        run.setTotalBudget(dec(req.totalBudget(), 2));
        run.setConstraints(new LinkedHashMap<>(body));
        run.setDatasetVersion(dataset);
        run.setCreatedAt(OffsetDateTime.now());
        List<String> details = List.of();
        try {
            Map<String, Object> r = ml.optimize(body);
            run.setFiscalYear((String) r.get("fiscal_year"));
            run.setStatus(OptimizationStatus.OPTIMAL);
            run.setTotalBudget(dec(r.get("total_budget"), 2));
            run.setObjectiveValue(dec(r.get("objective_value")));
            run.setSolverMessage(String.join("; ", (List<String>) r.get("binding_constraints")));
            run.setResultSnapshot(r);
            for (Object o : (List<Object>) r.get("allocation")) {
                Map<String, Object> a = map(o);
                run.addAllocation(new OptimizationAllocation((String) a.get("sector"), dec(a.get("current"), 2),
                        dec(a.get("recommended"), 2), dec(a.get("min"), 2), dec(a.get("max"), 2)));
            }
        } catch (MlServiceException e) {
            if (!"INFEASIBLE_CONSTRAINTS".equals(e.getCode())) {
                throw e;
            }
            run.setFiscalYear(req.fiscalYear());
            run.setStatus(OptimizationStatus.INFEASIBLE);
            run.setSolverMessage(e.getMessage() + (e.getDetails().isEmpty() ? "" : ": " + String.join("; ", e.getDetails())));
            details = e.getDetails();
        }
        OptimizationRun saved = runs.save(run);
        return toDto(saved, details);
    }

    private static Map<String, Object> boundMap(SectorBound sb) {
        Map<String, Object> m = new HashMap<>();
        m.put("min", sb.min());
        m.put("max", sb.max());
        m.put("min_share", sb.minShare());
        m.put("max_share", sb.maxShare());
        return m;
    }

    @Transactional(readOnly = true)
    public List<OptimizationDto> list(String stateCode) {
        return runs.search(stateCode).stream().map(r -> toDto(r, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public OptimizationDto get(Long id) {
        return toDto(require(id), List.of());
    }

    OptimizationRun require(Long id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("Optimization run " + id + " not found"));
    }

    private static OptimizationDto toDto(OptimizationRun r, List<String> details) {
        return new OptimizationDto(r.getId(), r.getState().getCode(), r.getFiscalYear(), r.getObjective().name(),
                r.getStatus().name(), r.getTotalBudget(), r.getObjectiveValue(), r.getSolverMessage(), details,
                r.getDatasetVersion().getLabel(), r.getCreatedAt(), r.getResultSnapshot());
    }
}
