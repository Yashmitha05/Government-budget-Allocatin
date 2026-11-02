package com.budgetintel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "state")
public class State {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 4)
    private String code;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(name = "formed_fiscal_year", length = 7)
    private String formedFiscalYear;

    @Column(name = "has_budget_data", nullable = false)
    private boolean hasBudgetData;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFormedFiscalYear() { return formedFiscalYear; }
    public void setFormedFiscalYear(String formedFiscalYear) { this.formedFiscalYear = formedFiscalYear; }
    public boolean isHasBudgetData() { return hasBudgetData; }
    public void setHasBudgetData(boolean hasBudgetData) { this.hasBudgetData = hasBudgetData; }
}
