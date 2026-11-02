package com.budgetintel.entity;

import com.budgetintel.entity.Enums.ComparisonBasis;
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

/** Predicted vs actual (or vs the government's own RE/BE estimate) for one stored forecast. */
@Entity
@Table(name = "prediction_deviation")
public class PredictionDeviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "forecast_id")
    private Forecast forecast;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observation_id")
    private Observation observation;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_basis", nullable = false, length = 12)
    private ComparisonBasis comparisonBasis;

    @Column(name = "actual_value", nullable = false, precision = 24, scale = 6)
    private BigDecimal actualValue;

    @Column(name = "absolute_error", nullable = false, precision = 24, scale = 6)
    private BigDecimal absoluteError;

    @Column(name = "percentage_error", precision = 12, scale = 4)
    private BigDecimal percentageError;

    @Column(name = "evaluated_at", nullable = false)
    private OffsetDateTime evaluatedAt;

    public Long getId() { return id; }
    public Forecast getForecast() { return forecast; }
    public void setForecast(Forecast forecast) { this.forecast = forecast; }
    public Observation getObservation() { return observation; }
    public void setObservation(Observation observation) { this.observation = observation; }
    public ComparisonBasis getComparisonBasis() { return comparisonBasis; }
    public void setComparisonBasis(ComparisonBasis comparisonBasis) { this.comparisonBasis = comparisonBasis; }
    public BigDecimal getActualValue() { return actualValue; }
    public void setActualValue(BigDecimal actualValue) { this.actualValue = actualValue; }
    public BigDecimal getAbsoluteError() { return absoluteError; }
    public void setAbsoluteError(BigDecimal absoluteError) { this.absoluteError = absoluteError; }
    public BigDecimal getPercentageError() { return percentageError; }
    public void setPercentageError(BigDecimal percentageError) { this.percentageError = percentageError; }
    public OffsetDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(OffsetDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
