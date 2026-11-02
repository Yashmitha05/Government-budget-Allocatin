package com.budgetintel.repository;

import com.budgetintel.entity.StressTest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StressTestRepository extends JpaRepository<StressTest, Long> {

    @Query("""
            select t from StressTest t join fetch t.state s
            where (:stateCode is null or s.code = :stateCode) order by t.createdAt desc""")
    List<StressTest> search(@Param("stateCode") String stateCode);
}
