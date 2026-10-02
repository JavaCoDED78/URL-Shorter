package com.javaded78.urlshorter.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.javaded78.urlshorter.TestcontainersConfiguration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Request-size limits (Task 20.1) against the running application.
 *
 * <p>Field validation already bounds URL (2048) and alias (64) lengths; this suite pins the third
 * limit — the raw JSON payload. Both declared sizes ({@code Content-Length}) and chunked bodies
 * without a declared size must be stopped, otherwise a client can stream an unbounded body.
 * Legitimate small payloads must keep working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class RequestSizeLimitIT {

  private static final int MAX_PAYLOAD = 16 * 1024;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void declaredOversizedPayloadRejectedWith413() {
    String huge = "{\"originalUrl\":\"https://example.com/" + "a".repeat(MAX_PAYLOAD) + "\"}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(huge, headers), Map.class);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("title")).isEqualTo("Payload too large");
    assertThat(response.getBody().get("status")).isEqualTo(413);
  }

  @Test
  void chunkedOversizedPayloadRejectedWith413() throws Exception {
    // raw socket: TestRestTemplate always declares Content-Length, so the
    // chunked path (no declared size) is exercised over a real socket
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("POST /api/v1/links HTTP/1.1\r\n"
                  + "Host: localhost:"
                  + port
                  + "\r\n"
                  + "Content-Type: application/json\r\n"
                  + "Transfer-Encoding: chunked\r\n"
                  + "Connection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      // valid JSON padded past 16 KB with a long string value — an
      // invalid body would fail parsing on the first token, before the
      // limit is ever reached
      String filler = "a".repeat(2048);
      for (int i = 0; i < 12; i++) { // 24 KB total, over the 16 KB limit
        String chunk =
            (i == 0 ? "{\"originalUrl\":\"https://example.com\",\"alias\":\"" : "")
                + filler
                + (i == 11 ? "x\"}" : "");
        out.write(
            (Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
      out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();

      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String statusLine = in.readLine();
      assertThat(statusLine).contains("413");
    }
  }

  @Test
  void legitimateSmallPayloadStillAccepted() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        restTemplate.exchange(
            url("/api/v1/links"),
            HttpMethod.POST,
            new HttpEntity<>("{\"originalUrl\":\"https://example.com\"}", headers),
            Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
