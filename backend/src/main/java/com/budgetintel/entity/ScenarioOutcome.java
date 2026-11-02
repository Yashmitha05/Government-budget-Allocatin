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
import java.math.BigDecimal;

/** Indicator effect of a scenario. predictedValue is null when the relationship is not statistically supported. */
@Entity
@Table(name = "scenario_outcome")
public class ScenarioOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id")
    private BudgetScenario scenario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "indicator_id")
    private Indicator indicator;

    @Column(name = "driver_sector", nullable = false, length = 24)
    private String driverSector;

    @Column(name = "current_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "predicted_value", precision = 20, scale = 4)
    private BigDecimal predictedValue;

    @Column(name = "lower_bound", precision = 20, scale = 4)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 20, scale = 4)
    private BigDecimal upperBound;

    @Column(nullable = false)
    private boolean supported;

    @Column(name = "method_note", nullable = false, length = 400)
    private String methodNote;

    public ScenarioOutcome() {
    }

    public ScenarioOutcome(Indicator indicator, String driverSector, BigDecimal currentValue, BigDecimal predictedValue,
                           BigDecimal lowerBound, BigDecimal upperBound, boolean supported, String methodNote) {
        this.indicator = indicator;
        this.driverSector = driverSector;
        this.currentValue = currentValue;
        this.predictedValue = predictedValue;
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.supported = supported;
        this.methodNote = methodNote;
    }

    public Long getId() { return id; }
    void setScenario(BudgetScenario scenario) { this.scenario = scenario; }
    public Indicator getIndicator() { return indicator; }
    public String getDriverSector() { return driverSector; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public BigDecimal getPredictedValue() { return predictedValue; }
    public boolean isSupported() { return supported; }
}
