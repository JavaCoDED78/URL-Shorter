package com.javaded78.urlshorter.domain.service;

import java.time.Instant;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.repository.LinkRepository;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Create/read application service (Task 4.4).
 *
 * <p>Create algorithm:
 * <ul>
 *   <li>validate the URL (scheme/host/length) and, when present, the alias
 *       (charset/length/reserved words, normalized to lowercase);</li>
 *   <li>with an alias: pre-check {@code existsByCode}, insert, and translate a
 *       constraint violation into {@link AliasAlreadyExistsException} — the
 *       pre-check is an optimization, the database unique index is the
 *       concurrency guarantee;</li>
 *   <li>without an alias: generate a Base62 code, insert, and retry a bounded
 *       number of times on collision.</li>
 * </ul>
 *
 * <p>Inserts use {@code saveAndFlush} so the unique-constraint outcome is
 * known inside the retry loop, not at an unpredictable commit point.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final AliasValidator aliasValidator;
    private final int maxCollisionRetries;

    public LinkService(LinkRepository repository, CodeGenerator codeGenerator,
            UrlValidator urlValidator, AliasValidator aliasValidator, ShortenerProperties properties) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.aliasValidator = aliasValidator;
        this.maxCollisionRetries = properties.create().maxCollisionRetries();
    }

    /**
     * Creates a new short link.
     *
     * @param originalUrl destination URL, validated
     * @param alias       optional custom alias; {@code null} or blank requests
     *                    a generated code
     * @param expiresAt   optional expiration instant (UTC); {@code null} means
     *                    the link never expires
     * @throws IllegalArgumentException       on invalid URL or alias
     * @throws AliasAlreadyExistsException    if the code is already taken
     * @throws IllegalStateException          if generated codes keep colliding
     */
    public Link create(String originalUrl, String alias, Instant expiresAt) {
        urlValidator.validate(originalUrl);
        if (alias == null || alias.isBlank()) {
            return createWithGeneratedCode(originalUrl, expiresAt);
        }
        return createWithAlias(originalUrl, alias, expiresAt);
    }

    /**
     * Returns the link with the given code.
     *
     * @throws LinkNotFoundException if no such link exists
     */
    public Link getByCode(String code) {
        return repository.findByCode(code)
                .map(this::toDomain)
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    /**
     * Resolves a code for redirection (Tasks 5.1, 5.2, 5.4): looks the link
     * up case-insensitively, then enforces the expiration policy.
     *
     * @throws LinkNotFoundException if no such link exists (404)
     * @throws LinkExpiredException  if the link's {@code expiresAt} has
     *                               passed (410)
     */
    public Link resolve(String code) {
        Link link = repository.findByCodeIgnoreCase(code)
                .map(this::toDomain)
                .orElseThrow(() -> new LinkNotFoundException(code));
        if (link.isExpired(Instant.now())) {
            throw new LinkExpiredException(code);
        }
        return link;
    }

    private Link createWithAlias(String originalUrl, String alias, Instant expiresAt) {
        String code = aliasValidator.validateAndNormalize(alias);
        if (repository.existsByCode(code)) {
            throw new AliasAlreadyExistsException(code);
        }
        try {
            return save(code, originalUrl, expiresAt);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race between existsByCode and insert — the database
            // unique index is the final authority.
            throw new AliasAlreadyExistsException(code);
        }
    }

    private Link createWithGeneratedCode(String originalUrl, Instant expiresAt) {
        for (int attempt = 1; attempt <= maxCollisionRetries; attempt++) {
            String code = codeGenerator.generate();
            try {
                return save(code, originalUrl, expiresAt);
            } catch (DataIntegrityViolationException ex) {
                log.debug("generated code collision on attempt {}/{}: {}", attempt, maxCollisionRetries, code);
            }
        }
        throw new IllegalStateException(
                "could not generate a unique code after " + maxCollisionRetries + " collision retries");
    }

    private Link save(String code, String originalUrl, Instant expiresAt) {
        LinkEntity entity = repository.saveAndFlush(new LinkEntity(code, originalUrl, expiresAt));
        return toDomain(entity);
    }

    private Link toDomain(LinkEntity entity) {
        return new Link(
                entity.getCode(),
                entity.getOriginalUrl(),
                entity.getExpiresAt(),
                entity.getClickCount(),
                entity.getCreatedAt());
    }
}
