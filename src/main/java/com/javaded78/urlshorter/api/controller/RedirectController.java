package com.javaded78.urlshorter.api.controller;

import com.javaded78.urlshorter.domain.model.ResolvedLink;
import com.javaded78.urlshorter.domain.service.ReadLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Root redirect endpoint: {@code GET /{code}} resolves the code through {@link ReadLinkService} and
 * answers {@link HttpStatus#FOUND 302 Found} with the destination in {@code Location}.
 *
 * <p>302 (not 301) is deliberate: a 301 can be cached by clients and intermediaries, silently
 * bypassing server-side click counting.
 *
 * <p>Only single-segment paths match, so {@code /api/**}, {@code /actuator/**} and {@code
 * /swagger-ui/**} keep their own handlers.
 */
@RestController
@Tag(name = "Redirects", description = "Root redirect endpoint")
public class RedirectController {

  private final ReadLinkService linkService;

  public RedirectController(ReadLinkService linkService) {
    this.linkService = linkService;
  }

  @Operation(
      summary = "Redirect to the original URL",
      description =
          "302 Found with the destination in the Location header. "
              + "302 (not 301) is deliberate: a 301 can be cached by clients and "
              + "intermediaries, silently bypassing server-side click counting.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "302",
        description = "Redirect to the original URL",
        headers = @Header(name = "Location", description = "Original URL")),
    @ApiResponse(
        responseCode = "404",
        description = "Link not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "410",
        description = "Link expired",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{code}")
  public ResponseEntity<Void> redirect(@PathVariable String code) {
    ResolvedLink link = linkService.redirect(code);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.originalUrl())).build();
  }
}
