package com.budgetintel.repository;

import com.budgetintel.entity.Enums.ForecastKind;
import com.budgetintel.entity.Forecast;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ForecastRepository extends JpaRepository<Forecast, Long> {

    @Query("""
            select f from Forecast f
            where f.modelVersion.id = :modelId and f.state.id = :stateId and f.indicator.id = :indicatorId
              and f.originFiscalYear = :origin and f.targetFiscalYear = :target""")
    Optional<Forecast> findKey(@Param("modelId") Long modelId, @Param("stateId") Long stateId,
                               @Param("indicatorId") Long indicatorId, @Param("origin") String origin,
                               @Param("target") String target);

    @Query("""
            select f from Forecast f join fetch f.state s join fetch f.indicator i join fetch f.modelVersion m
            where (:stateCode is null or s.code = :stateCode) and (:indicator is null or i.code = :indicator)
              and (:kind is null or f.kind = :kind)
            order by f.createdAt desc, i.code, f.targetFiscalYear""")
    List<Forecast> search(@Param("stateCode") String stateCode, @Param("indicator") String indicator,
                          @Param("kind") ForecastKind kind);

    /** Forecasts with no deviation record yet for the ACTUAL basis. */
    @Query("""
            select f from Forecast f join fetch f.state join fetch f.indicator join fetch f.modelVersion
            where not exists (select 1 from PredictionDeviation d where d.forecast = f
                              and d.comparisonBasis = com.budgetintel.entity.Enums.ComparisonBasis.ACTUAL)""")
    List<Forecast> findUnscored();

    long countByKind(ForecastKind kind);
}
