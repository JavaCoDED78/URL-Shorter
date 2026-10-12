package com.javaded78.urlshorter.infra.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA slice for {@link LinkEntity} (formerly the {@code LinkRepository} JPA interface).
 *
 * <p>This is the persistence-only port of the JPA layer: {@code LinkEntity}, the JPQL/ native
 * statements, and the {@code @Modifying(clearAutomatically, flushAutomatically)} context behaviour
 * all live here in {@code infra.persistence}. The domain {@code LinkRepository} is a pure
 * interface; {@link JpaLinkRepository} adapts this slice to the domain model.
 *
 * <p><b>No {@code @Transactional} lives here.</b> Spring Data method annotations control how the
 * persistence context behaves (flush before the next write, clear after the statement) but do not
 * open a transaction. Transaction boundaries live on the use-case (service) layer — {@code
 * WriteLinkService#create}/{@code delete} wrap the whole use-case (the delete's find→evict→
 * discard→delete sequence, the create's collision-retry loop); {@code CounterFlushService#flush}
 * wraps the drain→update→recovery round-trip — so they span more than a single isolated statement.
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
 *
 * <p><b>Transaction boundaries live on the use-case (service) layer, not here.</b> The Spring Data
 * method annotations carry {@code @Modifying(clearAutomatically, flushAutomatically)} — those
 * control how the persistence context behaves (clear after the statement, flush before the next
 * write) but do <b>not</b> start a transaction. The transaction itself is opened by
 * {@code @Transactional} on the {@link com.javaded78.urlshorter.domain.service.WriteLinkService}
 * and {@code CounterFlushService} methods, so it spans the whole use-case (e.g. the delete's
 * find→evict→discard→delete sequence) instead of a single isolated statement. See {@code
 * WriteLinkService}.
 */
public interface LinkJpaRepository extends JpaRepository<LinkEntity, String> {

  Optional<LinkEntity> findByCode(String code);

  /**
   * Case-insensitive lookup for the redirect endpoint (Sprint 5). Matches the {@code lower(code)}
   * functional unique index from V2, so PostgreSQL can serve it from the index.
   */
  @Query("SELECT l FROM LinkEntity l WHERE lower(l.code) = lower(:code)")
  Optional<LinkEntity> findByCodeIgnoreCase(@Param("code") String code);

  /**
   * Case-insensitive alias pre-check (create path). Mirrors {@link #findByCodeIgnoreCase} —
   * compares {@code lower(l.code) = lower(:code)} so it is served by the {@code lower(code)}
   * functional unique index and stays case-insensitive regardless of the caller's casing. This
   * keeps the pre-check aligned with the read path: even if the alias validator ever stops
   * normalizing to lowercase, a mixed-case alias such as {@code AbC} is still rejected here against
   * a stored {@code abc} instead of surfacing only as a unique-violation on insert.
   */
  @Query("SELECT COUNT(l) > 0 FROM LinkEntity l WHERE lower(l.code) = lower(:code)")
  boolean existsByCode(@Param("code") String code);

  long count();

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM LinkEntity l WHERE l.code = :code")
  int deleteByCode(@Param("code") String code);

  /**
   * Batch click-count update for the flush (Task 8.5): one statement for the whole drain batch via
   * {@code unnest}, not one UPDATE per code. The statement is atomic — on failure nothing is
   * applied, which the flush recovery relies on. The {@code @Transactional} that wraps this in a
   * transaction lives on {@code CounterFlushService#flush}.
   */
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
