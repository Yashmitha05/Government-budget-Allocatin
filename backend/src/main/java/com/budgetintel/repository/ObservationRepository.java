package com.budgetintel.repository;

import com.budgetintel.entity.Observation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObservationRepository extends JpaRepository<Observation, Long> {

    @Query("""
            select o from Observation o join fetch o.state s join fetch o.indicator i
            where o.datasetVersion.id = :datasetId and s.code = :stateCode
              and (:indicatorCode is null or i.code = :indicatorCode)
              and (:fromYear is null or o.fiscalYear >= :fromYear)
              and (:toYear is null or o.fiscalYear <= :toYear)
            order by i.code, o.fiscalYear""")
    List<Observation> search(@Param("datasetId") Long datasetId, @Param("stateCode") String stateCode,
                             @Param("indicatorCode") String indicatorCode, @Param("fromYear") String fromYear,
                             @Param("toYear") String toYear);

    @Query("""
            select o from Observation o join fetch o.state s join fetch o.indicator i
            where o.datasetVersion.id = :datasetId and s.code = :stateCode and o.fiscalYear in :years
              and i.category in :categories
            order by i.code, o.fiscalYear""")
    List<Observation> findForYears(@Param("datasetId") Long datasetId, @Param("stateCode") String stateCode,
                                   @Param("years") Collection<String> years,
                                   @Param("categories") Collection<String> categories);

    @Query("""
            select o from Observation o
            where o.datasetVersion.id = :datasetId and o.state.id = :stateId and o.indicator.id = :indicatorId
              and o.fiscalYear = :fiscalYear""")
    Optional<Observation> findOne(@Param("datasetId") Long datasetId, @Param("stateId") Long stateId,
                                  @Param("indicatorId") Long indicatorId, @Param("fiscalYear") String fiscalYear);

    long countByDatasetVersionId(Long datasetId);
}
