package com.budgetintel.entity;

import com.budgetintel.entity.Enums.StressSourceType;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stress_test")
public class StressTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @Column(name = "fiscal_year", nullable = false, length = 7)
    private String fiscalYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private StressSourceType sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> shocks;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> resultSnapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "stressTest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<StressTestResult> results = new ArrayList<>();

    public void addResult(StressTestResult r) {
        r.setStressTest(this);
        results.add(r);
    }

    public Long getId() { return id; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public String getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(String fiscalYear) { this.fiscalYear = fiscalYear; }
    public StressSourceType getSourceType() { return sourceType; }
    public void setSourceType(StressSourceType sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Map<String, Object> getShocks() { return shocks; }
    public void setShocks(Map<String, Object> shocks) { this.shocks = shocks; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(DatasetVersion datasetVersion) { this.datasetVersion = datasetVersion; }
    public Map<String, Object> getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(Map<String, Object> resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public List<StressTestResult> getResults() { return results; }
}
