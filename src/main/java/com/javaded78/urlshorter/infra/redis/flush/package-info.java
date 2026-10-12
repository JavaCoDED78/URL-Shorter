/**
 * Redis → PostgreSQL click-flush concern.
 *
 * <ul>
 *   <li>{@link CounterFlushService} — the infra orchestrator: drains every Redis counter, applies
 *       one batch UPDATE, and restores drained values to Redis on a failed DB write.
 *   <li>{@link ClickFlushJob} — the {@code @Scheduled} trigger that drives the flush on the
 *       configured interval, guarded by a distributed ShedLock.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package com.javaded78.urlshorter.infra.redis.flush;
