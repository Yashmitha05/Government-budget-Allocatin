package com.budgetintel.entity;

import com.budgetintel.entity.Enums.ScenarioType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "budget_scenario")
public class BudgetScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario_type", nullable = false, length = 24)
    private ScenarioType scenarioType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @Column(name = "fiscal_year", nullable = false, length = 7)
    private String fiscalYear;

    @Column(name = "baseline_data_status", nullable = false, length = 8)
    private String baselineDataStatus;

    @Column(nullable = false)
    private String description;

    @Column(name = "baseline_gfd", nullable = false, precision = 18, scale = 2)
    private BigDecimal baselineGfd;

    @Column(name = "scenario_gfd", nullable = false, precision = 18, scale = 2)
    private BigDecimal scenarioGfd;

    @Column(name = "baseline_gfd_pct_gsdp", precision = 8, scale = 4)
    private BigDecimal baselineGfdPctGsdp;

    @Column(name = "scenario_gfd_pct_gsdp", precision = 8, scale = 4)
    private BigDecimal scenarioGfdPctGsdp;

    @Column(name = "breaches_frbm_limit", nullable = false)
    private boolean breachesFrbmLimit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> resultSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ScenarioAllocation> allocations = new ArrayList<>();

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ScenarioOutcome> outcomes = new ArrayList<>();

    public void addAllocation(ScenarioAllocation a) {
        a.setScenario(this);
        allocations.add(a);
    }

    public void addOutcome(ScenarioOutcome o) {
        o.setScenario(this);
        outcomes.add(o);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ScenarioType getScenarioType() { return scenarioType; }
    public void setScenarioType(ScenarioType scenarioType) { this.scenarioType = scenarioType; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(String fiscalYear) { this.fiscalYear = fiscalYear; }
    public String getBaselineDataStatus() { return baselineDataStatus; }
    public void setBaselineDataStatus(String baselineDataStatus) { this.baselineDataStatus = baselineDataStatus; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getBaselineGfd() { return baselineGfd; }
    public void setBaselineGfd(BigDecimal baselineGfd) { this.baselineGfd = baselineGfd; }
    public BigDecimal getScenarioGfd() { return scenarioGfd; }
    public void setScenarioGfd(BigDecimal scenarioGfd) { this.scenarioGfd = scenarioGfd; }
    public BigDecimal getBaselineGfdPctGsdp() { return baselineGfdPctGsdp; }
    public void setBaselineGfdPctGsdp(BigDecimal v) { this.baselineGfdPctGsdp = v; }
    public BigDecimal getScenarioGfdPctGsdp() { return scenarioGfdPctGsdp; }
    public void setScenarioGfdPctGsdp(BigDecimal v) { this.scenarioGfdPctGsdp = v; }
    public boolean isBreachesFrbmLimit() { return breachesFrbmLimit; }
    public void setBreachesFrbmLimit(boolean breachesFrbmLimit) { this.breachesFrbmLimit = breachesFrbmLimit; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Map<String, Object> getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(Map<String, Object> resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public List<ScenarioAllocation> getAllocations() { return allocations; }
    public List<ScenarioOutcome> getOutcomes() { return outcomes; }
}
