package com.javaded78.urlshorter.domain.repository;

import com.javaded78.urlshorter.domain.model.Link;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for {@link Link}.
 *
 * <p>A <b>pure domain interface</b> — no Spring Data, no JPA, no {@code LinkEntity}: the domain
 * depends only on the {@link Link} model, and the {@link JpaLinkRepository} adapter ({@code
 * infra.persistence}) implements this port over Spring Data JPA. That is the single boundary that
 * keeps Spring Data out of the domain (rule 6).
 *
 * <p>Operation split:
 *
 * <ul>
 *   <li>{@link #existsByCode(String)} — case-insensitive alias pre-check optimization (matches the
 *       {@code lower(code)} unique index, same case semantics as {@link #findByCodeIgnoreCase});
 *       this deliberately does not depend on the caller lowercasing first, so it is decoupled from
 *       the alias validator's normalization policy.
 *   <li>Point lookups by {@code code} — {@link #findByCodeIgnoreCase(String)} (case-insensitive,
 *       matching the {@code lower(code)} unique index).
 *   <li>{@link #save(Link)} — insert with an immediate flush, so a unique-constraint collision
 *       surfaces inside {@link com.javaded78.urlshorter.domain.service.WriteLinkService}'s retry
 *       loop (the database unique index is the real concurrency guarantee).
 *   <li>Bulk {@link #batchIncrementClicks(Map)} — one statement for the whole drain batch; the
 *       flush relies on this being atomic.
 *   <li>{@link #deleteByCode(String)} — bulk delete of the row and its pending counter.
 * </ul>
 */
public interface LinkRepository {

  Optional<Link> findByCodeIgnoreCase(String code);

  boolean existsByCode(String code);

  /** Total number of links — used by tests and operational checks, not the hot path. */
  long count();

  /** Persists {@code link}; the unique constraint rejects duplicate codes (flushed immediately). */
  void save(Link link);

  /** Bulk-raises persisted click counts for the drained Redis counters (atomic batch). */
  int batchIncrementClicks(Map<String, Long> clicks);

  /** Removes the row by code (bulk DELETE, no entity load). */
  int deleteByCode(String code);
}
