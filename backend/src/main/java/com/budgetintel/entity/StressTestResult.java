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

@Entity
@Table(name = "stress_test_result")
public class StressTestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stress_test_id")
    private StressTest stressTest;

    @Column(name = "shock_name", nullable = false, length = 32)
    private String shockName;

    @Column(nullable = false, length = 48)
    private String metric;

    @Column(name = "normal_value", precision = 24, scale = 6)
    private BigDecimal normalValue;

    @Column(name = "stressed_value", precision = 24, scale = 6)
    private BigDecimal stressedValue;

    @Column(name = "breaches_limit", nullable = false)
    private boolean breachesLimit;

    public StressTestResult() {
    }

    public StressTestResult(String shockName, String metric, BigDecimal normalValue, BigDecimal stressedValue,
                            boolean breachesLimit) {
        this.shockName = shockName;
        this.metric = metric;
        this.normalValue = normalValue;
        this.stressedValue = stressedValue;
        this.breachesLimit = breachesLimit;
    }

    public Long getId() { return id; }
    void setStressTest(StressTest stressTest) { this.stressTest = stressTest; }
    public String getShockName() { return shockName; }
    public String getMetric() { return metric; }
    public BigDecimal getNormalValue() { return normalValue; }
    public BigDecimal getStressedValue() { return stressedValue; }
    public boolean isBreachesLimit() { return breachesLimit; }
}
