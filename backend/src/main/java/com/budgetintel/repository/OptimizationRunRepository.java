package com.budgetintel.repository;

import com.budgetintel.entity.OptimizationRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptimizationRunRepository extends JpaRepository<OptimizationRun, Long> {

    @Query("""
            select r from OptimizationRun r join fetch r.state s
            where (:stateCode is null or s.code = :stateCode) order by r.createdAt desc""")
    List<OptimizationRun> search(@Param("stateCode") String stateCode);
}
