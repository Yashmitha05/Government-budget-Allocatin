package com.budgetintel.entity;

import com.budgetintel.entity.Enums.OptimizationObjective;
import com.budgetintel.entity.Enums.OptimizationStatus;
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
@Table(name = "optimization_run")
public class OptimizationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @Column(name = "fiscal_year", length = 7)
    private String fiscalYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OptimizationObjective objective;

    @Column(name = "total_budget", precision = 18, scale = 2)
    private BigDecimal totalBudget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> constraints;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OptimizationStatus status;

    @Column(name = "objective_value", precision = 24, scale = 8)
    private BigDecimal objectiveValue;

    @Column(name = "solver_message")
    private String solverMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> resultSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OptimizationAllocation> allocations = new ArrayList<>();

    public void addAllocation(OptimizationAllocation a) {
        a.setRun(this);
        allocations.add(a);
    }

    public Long getId() { return id; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(String fiscalYear) { this.fiscalYear = fiscalYear; }
    public OptimizationObjective getObjective() { return objective; }
    public void setObjective(OptimizationObjective objective) { this.objective = objective; }
    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }
    public Map<String, Object> getConstraints() { return constraints; }
    public void setConstraints(Map<String, Object> constraints) { this.constraints = constraints; }
    public OptimizationStatus getStatus() { return status; }
    public void setStatus(OptimizationStatus status) { this.status = status; }
    public BigDecimal getObjectiveValue() { return objectiveValue; }
    public void setObjectiveValue(BigDecimal objectiveValue) { this.objectiveValue = objectiveValue; }
    public String getSolverMessage() { return solverMessage; }
    public void setSolverMessage(String solverMessage) { this.solverMessage = solverMessage; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public Map<String, Object> getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(Map<String, Object> resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public List<OptimizationAllocation> getAllocations() { return allocations; }
}
