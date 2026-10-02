package com.javaded78.urlshorter.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

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
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "links")
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
  private Instant updatedAt;

  /**
   * JPA-transient new-flag backing {@link Persistable#isNew()}. {@code true} for
   * application-constructed instances; cleared by {@link PostLoad} so entities loaded from the
   * database merge on save as usual.
   */
  @Transient private boolean isNew = true;

  public LinkEntity(String code, String originalUrl, Instant expiresAt) {
    this.code = code;
    this.originalUrl = originalUrl;
    this.expiresAt = expiresAt;
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

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
