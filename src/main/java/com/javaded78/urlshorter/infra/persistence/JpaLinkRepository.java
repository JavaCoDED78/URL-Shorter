package com.javaded78.urlshorter.infra.persistence;

import com.javaded78.urlshorter.domain.exception.DuplicateCodeException;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed {@link LinkRepository} (the {@code infra.persistence} implementation of the {@code
 * domain.repository.LinkRepository} port).
 *
 * <p>Converts the domain {@link com.javaded78.urlshorter.domain.model.Link} to/from the JPA {@link
 * LinkEntity} and delegates persistence to the Spring Data {@link LinkJpaRepository} slice. The
 * mapping lives here — the domain never sees {@code LinkEntity}.
 *
 * <p>Save semantics: {@link #save} persists with an immediate flush (via {@code
 * LinkJpaRepository#saveAndFlush}) so a unique-constraint collision surfaces inside the caller's
 * retry loop — this is a required semantic for the write path, not an optimization.
 */
@Repository
@RequiredArgsConstructor
public class JpaLinkRepository implements LinkRepository {

  private final LinkJpaRepository jpaRepository;

  @Override
  public Optional<Link> findByCodeIgnoreCase(String code) {
    return jpaRepository.findByCodeIgnoreCase(code).map(this::toLink);
  }

  @Override
  public boolean existsByCode(String code) {
    return jpaRepository.existsByCode(code);
  }

  @Override
  public long count() {
    return jpaRepository.count();
  }

  @Override
  public void save(Link link) {
    LinkEntity entity = toEntity(link);
    try {
      jpaRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException ex) {
      // The database unique index is the final authority on code uniqueness. Translate the
      // framework violation into a domain collision signal at the adapter boundary: the write
      // service only ever sees {@link DuplicateCodeException}, never a Spring type.
      throw new DuplicateCodeException(link.code());
    }
  }

  @Override
  public int deleteByCode(String code) {
    return jpaRepository.deleteByCode(code);
  }

  @Override
  public int batchIncrementClicks(Map<String, Long> clicks) {
    return jpaRepository.batchIncrementClickCounts(
        clicks.keySet().toArray(String[]::new), clicks.values().toArray(Long[]::new));
  }

  private Link toLink(LinkEntity entity) {
    return new Link(
        entity.getCode(),
        entity.getOriginalUrl(),
        entity.getExpiresAt(),
        entity.getClickCount(),
        entity.getCreatedAt());
  }

  private LinkEntity toEntity(Link link) {
    return new LinkEntity(link.code(), link.originalUrl(), link.expiresAt(), link.createdAt());
  }
}
