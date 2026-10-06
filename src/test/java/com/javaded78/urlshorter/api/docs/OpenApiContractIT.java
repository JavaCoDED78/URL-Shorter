package com.javaded78.urlshorter.api.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaded78.urlshorter.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * OpenAPI contract (Tasks 12.1–12.5): the API must be understandable without reading sources —
 * every endpoint has a summary, every DTO a description, and every documented error response is
 * declared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** path -> HTTP method -> response codes that must be documented. */
  private static final Map<String, Map<String, String[]>> DOCUMENTED_OPERATIONS =
      Map.of(
          "/api/v1/links", Map.of("post", new String[] {"201", "400", "409", "413"}),
          "/api/v1/links/{code}",
              Map.of(
                  "get", new String[] {"200", "404"},
                  "delete", new String[] {"204", "404"}),
          "/api/v1/links/{code}/stats", Map.of("get", new String[] {"200", "404"}),
          "/{code}", Map.of("get", new String[] {"302", "404", "410"}));

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  private JsonNode apiDocs;

  @BeforeEach
  void loadApiDocs() throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity(url("/v3/api-docs"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    apiDocs = MAPPER.readTree(response.getBody());
  }

  @Test
  void swaggerUiIsAvailable() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(url("/swagger-ui/index.html"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void apiInfoIsDocumented() {
    JsonNode info = apiDocs.required("info");
    assertThat(info.required("title").asText()).isEqualTo("URL Shortener API");
    assertThat(info.required("description").asText()).isNotBlank();
    assertThat(info.required("version").asText()).isNotBlank();
  }

  @Test
  void everyEndpointHasAnOperationWithSummary() {
    for (Map.Entry<String, Map<String, String[]>> path : DOCUMENTED_OPERATIONS.entrySet()) {
      JsonNode pathItem = apiDocs.required("paths").required(path.getKey());
      for (String method : path.getValue().keySet()) {
        JsonNode operation = pathItem.required(method);
        assertThat(operation.required("summary").asText())
            .as("%s %s must have a summary", method.toUpperCase(), path.getKey())
            .isNotBlank();
      }
    }
  }

  @Test
  void errorResponsesAreDocumentedForEveryEndpoint() {
    for (Map.Entry<String, Map<String, String[]>> path : DOCUMENTED_OPERATIONS.entrySet()) {
      JsonNode pathItem = apiDocs.required("paths").required(path.getKey());
      for (Map.Entry<String, String[]> operation : path.getValue().entrySet()) {
        JsonNode responses = pathItem.required(operation.getKey()).required("responses");
        for (String code : operation.getValue()) {
          assertThat(responses.has(code))
              .as(
                  "%s %s must document response %s",
                  operation.getKey().toUpperCase(), path.getKey(), code)
              .isTrue();
        }
      }
    }
  }

  @Test
  void documentedErrorsUseProblemDetailSchema() {
    JsonNode postResponses =
        apiDocs.required("paths").required("/api/v1/links").required("post").required("responses");
    for (String code : new String[] {"400", "409", "413"}) {
      JsonNode content = postResponses.required(code).required("content");
      JsonNode schema = content.elements().next().required("schema");
      assertThat(schema.required("$ref").asText())
          .as("response %s must reference the ProblemDetail schema", code)
          .contains("ProblemDetail");
    }
  }

  @Test
  void schemasAreDescribed() {
    JsonNode schemas = apiDocs.required("components").required("schemas");
    for (String name : new String[] {"CreateLinkRequest", "LinkResponse", "LinkStatsResponse"}) {
      assertThat(schemas.has(name)).as("schema %s must be present", name).isTrue();
      assertThat(schemas.required(name).required("description").asText())
          .as("schema %s must have a description", name)
          .isNotBlank();
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
