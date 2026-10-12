/**
 * Application services in the domain layer: {@link WriteLinkService} (write path), {@link
 * ReadLinkService} (read path), {@link CounterFlushService} and {@link MetricsRecorder}.
 *
 * <p>These beans carry the application logic — URL/alias validation, rate limiting, collision
 * retries, transactional writes — and depend on domain ports only ({@code domain.cache}, {@code
 * domain.counter}, {@code domain.repository}), never on infrastructure. The read and write paths
 * are separate beans so the hot redirect path never pays for a write-path gate.
 */
@org.jspecify.annotations.NullMarked
package com.javaded78.urlshorter.domain.service;
