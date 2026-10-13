package com.javaded78.urlshorter.domain.idempotency;

import com.javaded78.urlshorter.domain.model.Link;
import java.util.function.Supplier;

/**
 * Idempotency port for the create endpoint (Idempotency-Key).
 *
 * <p>Prevents a client retry — e.g. after an HTTP timeout — from creating a second link for the
 * same URL. The client supplies an opaque, stable {@code Idempotency-Key}; the first request that
 * carries it owns the key, creates the link and stores the result; every later request carrying the
 * same key <b>replays</b> that stored result instead of creating again.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>a retried request after the first completed replays the exact stored result (same code,
 *       201);
 *   <li>two concurrent requests carrying the same key converge on exactly one created link — the
 *       key is claimed atomically, the loser waits for the winner to finish and then replays;
 *   <li>a failed create releases the key, so a later retry with the same key may succeed;
 *   <li>on a Redis outage the store degrades to a plain (non-idempotent) create — availability
 *       wins, matching the cache-aside path — so no dedup happens until Redis is back.
 * </ul>
 *
 * <p>The store owns its TTL (how long a stored result is replayable, ~24h) and the wait timeout
 * (how long a loser waits for an in-flight winner). The implementation lives behind this port so
 * {@link com.javaded78.urlshorter.domain.service.WriteLinkService} never knows about Redis.
 */
public interface IdempotencyStore {

  /**
   * Runs {@code create} idempotently for a client-provided key and returns the link.
   *
   * <pre>
   *   key has a stored completed result → replay it (no create, no rate-limit probe)
   *   key free                          → claim it, run {@code create} once, store the success
   *   key owned by an in-flight request → wait (bounded), then replay the winner's result
   *   Redis unavailable                 → run {@code create} once, no claim/store (degrade)
   * </pre>
   *
   * <p>A failed {@code create} throws and releases the claim, so a subsequent retry with the same
   * key is not blocked by the failed attempt.
   *
   * @param idempotencyKey the client-provided key (opaque; never interpreted here)
   * @param create the single-create supplier, run at most once per key
   * @return the created (or replayed) link
   */
  Link execute(String idempotencyKey, Supplier<Link> create);
}
