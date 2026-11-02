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
@Table(name = "optimization_allocation")
public class OptimizationAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private OptimizationRun run;

    @Column(nullable = false, length = 24)
    private String sector;

    @Column(name = "current_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentAmount;

    @Column(name = "recommended_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal recommendedAmount;

    @Column(name = "min_bound", nullable = false, precision = 18, scale = 2)
    private BigDecimal minBound;

    @Column(name = "max_bound", nullable = false, precision = 18, scale = 2)
    private BigDecimal maxBound;

    public OptimizationAllocation() {
    }

    public OptimizationAllocation(String sector, BigDecimal currentAmount, BigDecimal recommendedAmount,
                                  BigDecimal minBound, BigDecimal maxBound) {
        this.sector = sector;
        this.currentAmount = currentAmount;
        this.recommendedAmount = recommendedAmount;
        this.minBound = minBound;
        this.maxBound = maxBound;
    }

    public Long getId() { return id; }
    void setRun(OptimizationRun run) { this.run = run; }
    public String getSector() { return sector; }
    public BigDecimal getCurrentAmount() { return currentAmount; }
    public BigDecimal getRecommendedAmount() { return recommendedAmount; }
    public BigDecimal getMinBound() { return minBound; }
    public BigDecimal getMaxBound() { return maxBound; }
}
