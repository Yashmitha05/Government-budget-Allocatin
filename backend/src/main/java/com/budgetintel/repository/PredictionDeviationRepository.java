package com.budgetintel.repository;

import com.budgetintel.entity.Enums.ComparisonBasis;
import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.entity.PredictionDeviation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PredictionDeviationRepository extends JpaRepository<PredictionDeviation, Long> {

    boolean existsByForecastIdAndComparisonBasis(Long forecastId, ComparisonBasis basis);

    @Query("""
            select d from PredictionDeviation d join fetch d.forecast f join fetch f.state s
              join fetch f.indicator i join fetch f.modelVersion m
            where (:stateCode is null or s.code = :stateCode) and (:indicator is null or i.code = :indicator)
              and (:version is null or m.versionNumber = :version) and (:basis is null or d.comparisonBasis = :basis)
              and (:kind is null or f.kind = :kind)
            order by i.code, s.code, f.targetFiscalYear, f.horizon""")
    List<PredictionDeviation> search(@Param("stateCode") String stateCode, @Param("indicator") String indicator,
                                     @Param("version") Integer version, @Param("basis") ComparisonBasis basis,
                                     @Param("kind") ForecastKind kind);

    /** Aggregate accuracy by indicator, model version, comparison basis, kind and horizon. */
    @Query("""
            select i.code, m.versionNumber, m.modelType, d.comparisonBasis, f.kind, f.horizon, count(d),
                   avg(d.absoluteError), avg(abs(d.percentageError)), avg(d.percentageError)
            from PredictionDeviation d join d.forecast f join f.indicator i join f.modelVersion m
            group by i.code, m.versionNumber, m.modelType, d.comparisonBasis, f.kind, f.horizon
            order by i.code, m.versionNumber, d.comparisonBasis, f.kind, f.horizon""")
    List<Object[]> summary();

    @Query("""
            select d from PredictionDeviation d join fetch d.forecast f join fetch f.state s join fetch f.modelVersion m
            where f.indicator.code = :indicator and m.modelType = :modelType and f.horizon = 1
              and d.comparisonBasis = com.budgetintel.entity.Enums.ComparisonBasis.ACTUAL
            order by f.targetFiscalYear""")
    List<PredictionDeviation> oneStepActuals(@Param("indicator") String indicator, @Param("modelType") String modelType);
}
