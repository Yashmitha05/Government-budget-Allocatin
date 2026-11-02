package com.budgetintel.repository;

import com.budgetintel.entity.BudgetScenario;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetScenarioRepository extends JpaRepository<BudgetScenario, Long> {

    @Query("""
            select b from BudgetScenario b join fetch b.state s
            where (:stateCode is null or s.code = :stateCode) order by b.createdAt desc""")
    List<BudgetScenario> search(@Param("stateCode") String stateCode);
}
