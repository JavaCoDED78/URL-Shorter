package com.javaded78.urlshorter.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * JPA slice for {@link LinkEntity} (the {@code infra.persistence} implementation of the {@code
 * domain.repository.LinkRepository} port, adapted by {@link JpaLinkRepository}).
 *
 * <p>This is the persistence-only slice — the port and its {@link JpaLinkRepository} adapter are
 * covered by the domain/service tests. Here we assert the JPA/PostgreSQL behaviour: the unique
 * constraint, the atomic bulk updates, case-insensitive lookup and {@code null} expiration.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class LinkJpaRepositoryTest {

  @Autowired LinkJpaRepository repository;

  @Test
  void savesAndFindsLinkByCode() {
    LinkEntity saved =
        repository.saveAndFlush(new LinkEntity("abc1234", "https://example.com", null));

    assertThat(saved.getCode()).isEqualTo("abc1234");
    assertThat(repository.findByCode("abc1234"))
        .hasValueSatisfying(
            link -> {
              assertThat(link.getOriginalUrl()).isEqualTo("https://example.com");
              assertThat(link.getClickCount()).isZero();
              assertThat(link.getCreatedAt()).isNotNull();
              assertThat(link.getUpdatedAt()).isNotNull();
            });
  }

  @Test
  void incrementsClickCountViaBatchUpdate() {
    repository.saveAndFlush(new LinkEntity("counter1", "https://example.com", null));

    int updated = repository.batchIncrementClickCounts(new String[] {"counter1"}, new Long[] {5L});

    assertThat(updated).isEqualTo(1);
    assertThat(repository.findByCode("counter1"))
        .hasValueSatisfying(link -> assertThat(link.getClickCount()).isEqualTo(5));
  }

  @Test
  void deletesLinkByCode() {
    repository.saveAndFlush(new LinkEntity("todelete", "https://example.com", null));

    int deleted = repository.deleteByCode("todelete");

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findByCode("todelete")).isEmpty();
    assertThat(repository.existsByCode("todelete")).isFalse();
  }

  @Test
  void rejectsDuplicateCode() {
    repository.saveAndFlush(new LinkEntity("dupe", "https://example.com", null));

    assertThatThrownBy(
            () -> repository.saveAndFlush(new LinkEntity("dupe", "https://other.com", null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void persistsNullExpiresAt() {
    repository.saveAndFlush(new LinkEntity("noexp", "https://example.com", null));

    assertThat(repository.findByCode("noexp"))
        .hasValueSatisfying(link -> assertThat(link.getExpiresAt()).isNull());
  }

  @Test
  void persistsExpiresAt() {
    Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");

    repository.saveAndFlush(new LinkEntity("withexp", "https://example.com", expiresAt));

    assertThat(repository.findByCode("withexp"))
        .hasValueSatisfying(link -> assertThat(link.getExpiresAt()).isEqualTo(expiresAt));
  }
}
