package com.javaded78.urlshorter.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.javaded78.urlshorter.config.ShortenerProperties;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.infra.persistence.LinkEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LinkServiceTest {

    private final ShortenerProperties properties = new ShortenerProperties(
            new ShortenerProperties.Url(2048, List.of("http", "https")),
            new ShortenerProperties.Alias(64, Set.of("api")),
            new ShortenerProperties.Create(3));

    private final com.javaded78.urlshorter.domain.repository.LinkRepository repository =
            mock(com.javaded78.urlshorter.domain.repository.LinkRepository.class);
    private final CodeGenerator codeGenerator = mock(CodeGenerator.class);

    private final LinkService service = new LinkService(
            repository, codeGenerator, new UrlValidator(properties), new AliasValidator(properties), properties);

    private static LinkEntity persisted(LinkEntity entity) {
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    @Test
    void createsLinkWithGeneratedCode() {
        when(codeGenerator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any(LinkEntity.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

        Link link = service.create("https://example.com", null, null);

        assertThat(link.code()).isEqualTo("abc1234");
        assertThat(link.originalUrl()).isEqualTo("https://example.com");
        assertThat(link.clickCount()).isZero();
        assertThat(link.expiresAt()).isNull();
        assertThat(link.createdAt()).isNotNull();
    }

    @Test
    void retriesOnGeneratedCodeCollision() {
        when(codeGenerator.generate()).thenReturn("taken1", "fresh12");
        when(repository.saveAndFlush(any(LinkEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

        Link link = service.create("https://example.com", null, null);

        assertThat(link.code()).isEqualTo("fresh12");
        verify(codeGenerator, times(2)).generate();
    }

    @Test
    void failsWhenCollisionRetriesAreExhausted() {
        when(codeGenerator.generate()).thenReturn("taken1");
        when(repository.saveAndFlush(any(LinkEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create("https://example.com", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision");
        verify(codeGenerator, times(3)).generate();
    }

    @Test
    void createsLinkWithNormalizedAlias() {
        when(repository.saveAndFlush(any(LinkEntity.class)))
                .thenAnswer(invocation -> persisted(invocation.getArgument(0)));

        Link link = service.create("https://example.com", "My-Link", null);

        assertThat(link.code()).isEqualTo("my-link");
    }

    @Test
    void rejectsAliasThatAlreadyExists() {
        when(repository.existsByCode("taken1")).thenReturn(true);

        assertThatThrownBy(() -> service.create("https://example.com", "taken1", null))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void rejectsAliasThatWinsTheRaceBetweenCheckAndInsert() {
        when(repository.existsByCode("racer1")).thenReturn(false);
        when(repository.saveAndFlush(any(LinkEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create("https://example.com", "racer1", null))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void rejectsInvalidOriginalUrl() {
        assertThatThrownBy(() -> service.create("not-a-url", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReservedAlias() {
        assertThatThrownBy(() -> service.create("https://example.com", "api", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsExistingLinkByCode() {
        LinkEntity entity = persisted(new LinkEntity("abc1234", "https://example.com", null));
        when(repository.findByCode("abc1234")).thenReturn(java.util.Optional.of(entity));

        Link link = service.getByCode("abc1234");

        assertThat(link.originalUrl()).isEqualTo("https://example.com");
    }

    @Test
    void throwsWhenLinkNotFound() {
        when(repository.findByCode("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getByCode("missing"))
                .isInstanceOf(LinkNotFoundException.class);
    }
}
