package com.budgetintel.repository;

import com.budgetintel.entity.ModelVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    @Query("select m from ModelVersion m join fetch m.targetIndicator i where i.code = :target and m.versionNumber = :version")
    Optional<ModelVersion> findVersion(@Param("target") String target, @Param("version") int version);

    @Query("select m from ModelVersion m join fetch m.targetIndicator i where i.code = :target and m.active = true")
    Optional<ModelVersion> findActive(@Param("target") String target);

    @Query("""
            select m from ModelVersion m join fetch m.targetIndicator i left join fetch m.parentVersion
            where (:target is null or i.code = :target) order by i.code, m.versionNumber""")
    List<ModelVersion> list(@Param("target") String target);
}
