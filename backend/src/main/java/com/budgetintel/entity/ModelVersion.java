package com.budgetintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Metadata of a model version in the ML service registry, mirrored for joins and the UI. */
@Entity
@Table(name = "model_version")
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_indicator_id")
    private Indicator targetIndicator;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "model_type", nullable = false, length = 48)
    private String modelType;

    @Column(name = "dataset_label", nullable = false, length = 64)
    private String datasetLabel;

    @Column(name = "dataset_hash", nullable = false, length = 64, columnDefinition = "bpchar(64)")
    private String datasetHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_version_id")
    private ModelVersion parentVersion;

    @Column(name = "trained_at", nullable = false)
    private OffsetDateTime trainedAt;

    @Column(name = "train_year_from", nullable = false, length = 7)
    private String trainYearFrom;

    @Column(name = "train_year_to", nullable = false, length = 7)
    private String trainYearTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(nullable = false)
    private boolean calibrated;

    @Column(name = "artifact_path", nullable = false)
    private String artifactPath;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public Long getId() { return id; }
    public Indicator getTargetIndicator() { return targetIndicator; }
    public void setTargetIndicator(Indicator targetIndicator) { this.targetIndicator = targetIndicator; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public String getDatasetLabel() { return datasetLabel; }
    public void setDatasetLabel(String datasetLabel) { this.datasetLabel = datasetLabel; }
    public String getDatasetHash() { return datasetHash; }
    public void setDatasetHash(String datasetHash) { this.datasetHash = datasetHash; }
    public ModelVersion getParentVersion() { return parentVersion; }
    public void setParentVersion(ModelVersion parentVersion) { this.parentVersion = parentVersion; }
    public OffsetDateTime getTrainedAt() { return trainedAt; }
    public void setTrainedAt(OffsetDateTime trainedAt) { this.trainedAt = trainedAt; }
    public String getTrainYearFrom() { return trainYearFrom; }
    public void setTrainYearFrom(String trainYearFrom) { this.trainYearFrom = trainYearFrom; }
    public String getTrainYearTo() { return trainYearTo; }
    public void setTrainYearTo(String trainYearTo) { this.trainYearTo = trainYearTo; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public boolean isCalibrated() { return calibrated; }
    public void setCalibrated(boolean calibrated) { this.calibrated = calibrated; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
