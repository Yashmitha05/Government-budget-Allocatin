package com.budgetintel.repository;

import com.budgetintel.entity.DatasetVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, Long> {

    Optional<DatasetVersion> findByContentHash(String contentHash);

    Optional<DatasetVersion> findByCurrentTrue();

    @Modifying
    @Query("update DatasetVersion d set d.current = false where d.current = true")
    int clearCurrent();
}
