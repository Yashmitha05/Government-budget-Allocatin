package com.budgetintel.repository;

import com.budgetintel.entity.State;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateRepository extends JpaRepository<State, Long> {

    Optional<State> findByCode(String code);

    List<State> findAllByOrderByNameAsc();
}
