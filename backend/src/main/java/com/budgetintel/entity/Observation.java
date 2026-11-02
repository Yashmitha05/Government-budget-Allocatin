package com.budgetintel.entity;

import com.budgetintel.entity.Enums.DataStatus;
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

/** A published (or derived) value of one indicator for one state and fiscal year: the budget record. */
@Entity
@Table(name = "observation")
public class Observation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id")
    private DatasetVersion datasetVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id")
    private State state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "indicator_id")
    private Indicator indicator;

    @Column(name = "fiscal_year", nullable = false, length = 7)
    private String fiscalYear;

    @Column(nullable = false, precision = 24, scale = 6)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_status", nullable = false, length = 8)
    private DataStatus dataStatus;

    @Column(name = "footnote_flag", length = 16)
    private String footnoteFlag;

    public Long getId() { return id; }
    public DatasetVersion getDatasetVersion() { return datasetVersion; }
    public State getState() { return state; }
    public Indicator getIndicator() { return indicator; }
    public String getFiscalYear() { return fiscalYear; }
    public BigDecimal getValue() { return value; }
    public DataStatus getDataStatus() { return dataStatus; }
    public String getFootnoteFlag() { return footnoteFlag; }
}
