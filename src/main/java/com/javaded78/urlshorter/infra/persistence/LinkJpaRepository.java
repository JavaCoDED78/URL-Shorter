package com.javaded78.urlshorter.infra.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA slice for {@link LinkEntity} (formerly the {@code LinkRepository} JPA interface).
 *
 * <p>This is the persistence-only port of the JPA layer: {@code LinkEntity}, the JPQL/ native
 * statements and the {@code @Transactional} boundaries all live here in {@code infra.persistence}.
 * The domain {@code LinkRepository} is a pure interface; {@link JpaLinkRepository} adapts this
 * slice to the domain model.
 *
 * <p>Operation split:
 *
 * <ul>
 *   <li>Point lookups by {@code code} — the primary-key index makes them cheap, and entities are
 *       needed in full.
 *   <li>Bulk JPQL / native bulk updates and {@code DELETE} — single statements, no entity load into
 *       the persistence context. Click increments are the hot write path (flush), so
 *       load-modify-save would be wasted work and a lost-update risk.
 * </ul>
 */
public interface LinkJpaRepository extends JpaRepository<LinkEntity, String> {

  Optional<LinkEntity> findByCode(String code);

  /**
   * Case-insensitive lookup for the redirect endpoint (Sprint 5). Matches the {@code lower(code)}
   * functional unique index from V2, so PostgreSQL can serve it from the index.
   */
  @Query("SELECT l FROM LinkEntity l WHERE lower(l.code) = lower(:code)")
  Optional<LinkEntity> findByCodeIgnoreCase(@Param("code") String code);

  boolean existsByCode(String code);

  long count();

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE LinkEntity l SET l.clickCount = l.clickCount + :increment WHERE l.code = :code")
  int incrementClickCount(@Param("code") String code, @Param("increment") long increment);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM LinkEntity l WHERE l.code = :code")
  int deleteByCode(@Param("code") String code);

  /**
   * Batch click-count update for the flush (Task 8.5): one statement for all drained counters via
   * {@code unnest}, not one UPDATE per code. The statement is atomic — on failure nothing is
   * applied, which the flush recovery relies on.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
            UPDATE links l
            SET click_count = l.click_count + batch.inc
            FROM unnest(:codes, :increments) AS batch(code, inc)
            WHERE l.code = batch.code
            """,
      nativeQuery = true)
  int batchIncrementClickCounts(
      @Param("codes") String[] codes, @Param("increments") Long[] increments);
}
