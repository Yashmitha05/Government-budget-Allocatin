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
@Table(name = "scenario_allocation")
public class ScenarioAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id")
    private BudgetScenario scenario;

    @Column(nullable = false, length = 24)
    private String sector;

    @Column(name = "baseline_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baselineAmount;

    @Column(name = "scenario_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal scenarioAmount;

    @Column(name = "change_pct", precision = 10, scale = 4)
    private BigDecimal changePct;

    public ScenarioAllocation() {
    }

    public ScenarioAllocation(String sector, BigDecimal baselineAmount, BigDecimal scenarioAmount, BigDecimal changePct) {
        this.sector = sector;
        this.baselineAmount = baselineAmount;
        this.scenarioAmount = scenarioAmount;
        this.changePct = changePct;
    }

    public Long getId() { return id; }
    public BudgetScenario getScenario() { return scenario; }
    void setScenario(BudgetScenario scenario) { this.scenario = scenario; }
    public String getSector() { return sector; }
    public BigDecimal getBaselineAmount() { return baselineAmount; }
    public BigDecimal getScenarioAmount() { return scenarioAmount; }
    public BigDecimal getChangePct() { return changePct; }
}
