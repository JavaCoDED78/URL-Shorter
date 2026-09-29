package com.javaded78.urlshorter.domain.repository;

import java.util.Optional;

import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence operations for {@link LinkEntity} (Task 2.2). The interface
 * lives in the domain; {@code LinkEntity} and the JPA mappings stay in
 * {@code infra.persistence} — this import is the deliberate JPA boundary of
 * the domain: the port is a Spring Data repository, so the domain already
 * speaks JPA through it and the entity type appears in signatures.
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

    /**
     * Case-insensitive lookup for the redirect endpoint (Sprint 5). Matches
     * the {@code lower(code)} functional unique index from V2, so PostgreSQL
     * can serve it from the index.
     */
    @Query("SELECT l FROM LinkEntity l WHERE lower(l.code) = lower(:code)")
    Optional<LinkEntity> findByCodeIgnoreCase(@Param("code") String code);

    boolean existsByCode(String code);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LinkEntity l SET l.clickCount = l.clickCount + :increment WHERE l.code = :code")
    int incrementClickCount(@Param("code") String code, @Param("increment") long increment);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LinkEntity l WHERE l.code = :code")
    int deleteByCode(@Param("code") String code);

    /**
     * Batch click-count update for the flush (Task 8.5): one statement for
     * all drained counters via {@code unnest}, not one UPDATE per code. The
     * statement is atomic — on failure nothing is applied, which the flush
     * recovery relies on.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE links l
            SET click_count = l.click_count + batch.inc
            FROM unnest(:codes, :increments) AS batch(code, inc)
            WHERE l.code = batch.code
            """, nativeQuery = true)
    int batchIncrementClickCounts(@Param("codes") String[] codes, @Param("increments") Long[] increments);
}
