package com.javaded78.urlshorter.api.controller;

import java.net.URI;

import com.javaded78.urlshorter.api.dto.CreateLinkRequest;
import com.javaded78.urlshorter.api.dto.LinkResponse;
import com.javaded78.urlshorter.api.dto.LinkStatsResponse;
import com.javaded78.urlshorter.domain.model.Link;
import com.javaded78.urlshorter.domain.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Tag(name = "Links", description = "Create, read, get statistics and delete short links")
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @Operation(summary = "Create a short link",
            description = "Stores a new link under a generated Base62 code or the given "
                    + "custom alias.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Link created"),
            @ApiResponse(responseCode = "400", description = "Invalid request — bad URL, "
                    + "alias or expiration",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Code or alias already taken",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        Link created = linkService.create(request.originalUrl(), request.alias(), request.expiresAt());
        LinkResponse body = LinkResponse.from(created);
        return ResponseEntity
                .created(URI.create("/api/v1/links/" + created.code()))
                .body(body);
    }

    @Operation(summary = "Get link details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Link found"),
            @ApiResponse(responseCode = "404", description = "Link not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/{code}")
    public LinkResponse getByCode(@PathVariable String code) {
        return LinkResponse.from(linkService.getByCode(code));
    }

    @Operation(summary = "Get click statistics",
            description = "Returns the logically current count: persisted clicks plus "
                    + "clicks still pending in Redis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statistics returned"),
            @ApiResponse(responseCode = "404", description = "Link not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code) {
        return LinkStatsResponse.from(linkService.getStats(code));
    }

    @Operation(summary = "Delete a link",
            description = "Removes the link from PostgreSQL, evicts the Redis cache entry "
                    + "and discards its pending click counter.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Link deleted"),
            @ApiResponse(responseCode = "404", description = "Link not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        linkService.delete(code);
        return ResponseEntity.noContent().build();
    }
}
