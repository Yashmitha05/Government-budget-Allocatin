package com.budgetintel.controller;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.dto.ReferenceDtos.DatasetDto;
import com.budgetintel.dto.ReferenceDtos.DatasetImportResult;
import com.budgetintel.dto.ReferenceDtos.IndicatorDto;
import com.budgetintel.dto.ReferenceDtos.StateDto;
import com.budgetintel.service.DatasetImportService;
import com.budgetintel.service.ReferenceDataService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReferenceController {

    private final ReferenceDataService reference;
    private final DatasetImportService importer;
    private final MlServiceClient ml;

    public ReferenceController(ReferenceDataService reference, DatasetImportService importer, MlServiceClient ml) {
        this.reference = reference;
        this.importer = importer;
        this.ml = ml;
    }

    @GetMapping("/states")
    public List<StateDto> states() {
        return reference.listStates();
    }

    @GetMapping("/indicators")
    public List<IndicatorDto> indicators() {
        return reference.listIndicators();
    }

    @GetMapping("/datasets/current")
    public DatasetDto currentDataset() {
        return reference.currentDatasetDto();
    }

    /** Spending-to-indicator relationships estimated by the ML service (for the methodology panel). */
    @GetMapping("/impact-models")
    public List<Map<String, Object>> impactModels() {
        return ml.impact();
    }

    @PostMapping("/admin/datasets/import")
    public DatasetImportResult importDataset() {
        return importer.importCurrent();
    }
}
