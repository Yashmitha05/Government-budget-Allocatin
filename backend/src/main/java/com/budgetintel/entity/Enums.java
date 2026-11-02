package com.budgetintel.entity;

/** Enumerations stored as strings; values match the CHECK constraints in V1__initial_schema.sql. */
public final class Enums {

    private Enums() {
    }

    public enum DataStatus { ACCOUNTS, ACTUAL, RE, BE, OBSERVED, DERIVED;
        public boolean isActual() {
            return this == ACCOUNTS || this == ACTUAL;
        }
    }

    public enum ForecastKind { BACKTEST, LIVE }

    public enum ComparisonBasis { ACTUAL, GOVT_RE, GOVT_BE }

    public enum ScenarioType { BALANCED, HEALTH_PRIORITY, SOCIAL_PRIORITY, INFRASTRUCTURE_PRIORITY, CUSTOM }

    public enum OptimizationObjective { MIN_ADJUSTMENT, MAX_CAPITAL_OUTLAY, OUTCOME }

    public enum OptimizationStatus { OPTIMAL, INFEASIBLE }

    public enum StressSourceType { CURRENT, SCENARIO, OPTIMIZATION }
}
