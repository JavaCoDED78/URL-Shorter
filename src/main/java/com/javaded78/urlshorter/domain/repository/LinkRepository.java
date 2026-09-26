package com.javaded78.urlshorter.domain.repository;

import java.util.Optional;

import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence operations for {@link LinkEntity}.
 *
 * <p>Operation split (Task 2.4):
 * <ul>
 *   <li>JPA derived queries: point lookups by {@code code} — the primary key
 *       index makes them cheap, and entities are needed in full.</li>
 *   <li>Bulk JPQL: {@code UPDATE} of click counts and {@code DELETE} by
 *       {@code code} — single statements, no entity load into the persistence
 *       context. Click increments are the hot write path (flush, Sprint 8),
 *       so load-modify-save would be wasted work and a lost-update risk.</li>
 * </ul>
 */
public interface LinkRepository extends JpaRepository<LinkEntity, String> {

    Optional<LinkEntity> findByCode(String code);

    boolean existsByCode(String code);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LinkEntity l SET l.clickCount = l.clickCount + :increment WHERE l.code = :code")
    int incrementClickCount(@Param("code") String code, @Param("increment") long increment);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LinkEntity l WHERE l.code = :code")
    int deleteByCode(@Param("code") String code);
}
