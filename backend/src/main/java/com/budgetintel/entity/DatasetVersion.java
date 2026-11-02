package com.budgetintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dataset_version")
public class DatasetVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String label;

    @Column(name = "source_summary", nullable = false)
    private String sourceSummary;

    @Column(name = "content_hash", nullable = false, unique = true, length = 64, columnDefinition = "bpchar(64)")
    private String contentHash;

    @Column(name = "built_at", nullable = false, length = 32)
    private String builtAt;

    @Column(name = "imported_at", nullable = false)
    private OffsetDateTime importedAt;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_report", columnDefinition = "jsonb")
    private Map<String, Object> qualityReport;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSourceSummary() { return sourceSummary; }
    public void setSourceSummary(String sourceSummary) { this.sourceSummary = sourceSummary; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getBuiltAt() { return builtAt; }
    public void setBuiltAt(String builtAt) { this.builtAt = builtAt; }
    public OffsetDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
    public Map<String, Object> getQualityReport() { return qualityReport; }
    public void setQualityReport(Map<String, Object> qualityReport) { this.qualityReport = qualityReport; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}
