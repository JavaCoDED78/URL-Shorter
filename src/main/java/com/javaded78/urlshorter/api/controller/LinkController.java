package com.javaded78.urlshorter.api.controller;

import java.net.URI;

import com.javaded78.urlshorter.api.dto.CreateLinkRequest;
import com.javaded78.urlshorter.api.dto.LinkResponse;
import com.javaded78.urlshorter.api.dto.LinkStatsResponse;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.service.LinkService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link API endpoints (Tasks 4.5, 4.6). Controllers stay thin: validate the
 * request, delegate to the service, map the result to DTOs.
 */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        Link created = linkService.create(request.originalUrl(), request.alias(), request.expiresAt());
        LinkResponse body = LinkResponse.from(created);
        return ResponseEntity
                .created(URI.create("/api/links/" + created.code()))
                .body(body);
    }

    @GetMapping("/{code}")
    public LinkResponse getByCode(@PathVariable String code) {
        return LinkResponse.from(linkService.getByCode(code));
    }

    @GetMapping("/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code) {
        return LinkStatsResponse.from(linkService.getStats(code));
    }
}
