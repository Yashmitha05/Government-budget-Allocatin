package com.budgetintel.repository;

import com.budgetintel.entity.Indicator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndicatorRepository extends JpaRepository<Indicator, Long> {

    Optional<Indicator> findByCode(String code);

    List<Indicator> findAllByOrderByCategoryAscCodeAsc();
}
