package com.javaded78.urlshorter.api.controller;

import java.net.URI;

import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.service.LinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Root redirect endpoint (Tasks 5.1, 5.4): {@code GET /{code}} resolves the
 * code through {@link LinkService} and answers
 * {@link HttpStatus#FOUND 302 Found} with the destination in {@code Location}.
 *
 * <p>302 (not 301) is deliberate: a 301 can be cached by clients and
 * intermediaries, silently bypassing server-side click counting.
 *
 * <p>Only single-segment paths match, so {@code /api/**}, {@code /actuator/**}
 * and {@code /swagger-ui/**} keep their own handlers.
 */
@RestController
public class RedirectController {

    private final LinkService linkService;

    public RedirectController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Link link = linkService.resolve(code);
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(link.originalUrl()))
                .build();
    }
}
