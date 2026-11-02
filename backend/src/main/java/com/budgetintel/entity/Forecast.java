package com.budgetintel.entity;

import com.budgetintel.entity.Enums.ForecastKind;
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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A stored prediction. Its id is the user-facing Prediction ID. */
@Entity
@Table(name = "forecast")
public class Forecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_version_id")
    private ModelVersion modelVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "indicator_id")
    private Indicator indicator;

    @Column(name = "origin_fiscal_year", nullable = false, length = 7)
    private String originFiscalYear;

    @Column(name = "target_fiscal_year", nullable = false, length = 7)
    private String targetFiscalYear;

    @Column(nullable = false)
    private short horizon;

    @Column(name = "predicted_value", nullable = false, precision = 24, scale = 6)
    private BigDecimal predictedValue;

    @Column(name = "lower_bound", precision = 24, scale = 6)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 24, scale = 6)
    private BigDecimal upperBound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ForecastKind kind;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public ModelVersion getModelVersion() { return modelVersion; }
    public void setModelVersion(ModelVersion modelVersion) { this.modelVersion = modelVersion; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public Indicator getIndicator() { return indicator; }
    public void setIndicator(Indicator indicator) { this.indicator = indicator; }
    public String getOriginFiscalYear() { return originFiscalYear; }
    public void setOriginFiscalYear(String originFiscalYear) { this.originFiscalYear = originFiscalYear; }
    public String getTargetFiscalYear() { return targetFiscalYear; }
    public void setTargetFiscalYear(String targetFiscalYear) { this.targetFiscalYear = targetFiscalYear; }
    public short getHorizon() { return horizon; }
    public void setHorizon(short horizon) { this.horizon = horizon; }
    public BigDecimal getPredictedValue() { return predictedValue; }
    public void setPredictedValue(BigDecimal predictedValue) { this.predictedValue = predictedValue; }
    public BigDecimal getLowerBound() { return lowerBound; }
    public void setLowerBound(BigDecimal lowerBound) { this.lowerBound = lowerBound; }
    public BigDecimal getUpperBound() { return upperBound; }
    public void setUpperBound(BigDecimal upperBound) { this.upperBound = upperBound; }
    public ForecastKind getKind() { return kind; }
    public void setKind(ForecastKind kind) { this.kind = kind; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
