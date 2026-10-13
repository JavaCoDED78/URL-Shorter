package com.javaded78.urlshorter.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * JPA mapping of the {@code links} table (Flyway V1).
 *
 * <p>{@code code} is a natural primary key assigned by the application (Base62), so no generated
 * identity strategy is used. Timestamps are UTC {@link Instant}s, matching the TIMESTAMPTZ columns.
 *
 * <p>Implements {@link Persistable} because the id is assigned before the first save: without an
 * explicit new-flag, Spring Data JPA would treat every instance as detached and merge instead of
 * persist, silently overwriting an existing row on duplicate code instead of letting the database
 * reject it.
 *
 * <p>Timestamps come from a single source. {@code created_at} is mapped explicitly from the domain
 * (the {@link com.javaded78.urlshorter.domain.service.WriteLinkService} supplies {@code
 * clock.instant()}) — it is not stamped by the persistence layer, so tests that drive the {@link
 * Clock} see the deterministic instant round-trip. {@code updated_at} is owned by Spring Data JPA
 * auditing over the same {@link Clock} bean, so it always advances on update without the entity
 * ever calling {@code Instant.now()} itself.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "links")
@EntityListeners(AuditingEntityListener.class)
public class LinkEntity implements Persistable<String> {

  @Id
  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @Column(name = "original_url", nullable = false)
  private String originalUrl;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "click_count", nullable = false)
  private long clickCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  @LastModifiedDate
  private Instant updatedAt;

  /**
   * JPA-transient new-flag backing {@link Persistable#isNew()}. {@code true} for
   * application-constructed instances; cleared by {@link PostLoad} so entities loaded from the
   * database merge on save as usual.
   */
  @Transient private boolean isNew = true;

  public LinkEntity(String code, String originalUrl, Instant expiresAt, Instant createdAt) {
    this.code = code;
    this.originalUrl = originalUrl;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  @Override
  public String getId() {
    return this.code;
  }

  @Override
  public boolean isNew() {
    return this.isNew;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }
}
