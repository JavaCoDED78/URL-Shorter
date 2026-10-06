package com.javaded78.urlshorter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

class ProblemDetailWriterTest {

  /** Real {@link ServletOutputStream} backed by a {@link ByteArrayOutputStream}. */
  private static final class RecordingOutputStream extends ServletOutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      buffer.write(b);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {
      throw new UnsupportedOperationException();
    }

    byte[] bytes() {
      return buffer.toByteArray();
    }
  }

  @Test
  void writesStatusContentTypeAndBody() throws Exception {
    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    RecordingOutputStream out = new RecordingOutputStream();
    Mockito.when(response.getOutputStream()).thenReturn(out);
    ObjectMapper mapper = new ObjectMapper();
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "bad");
    problem.setTitle("Bad request");

    ProblemDetailWriter.write(response, problem);

    Mockito.verify(response).setStatus(HttpStatus.BAD_REQUEST.value());
    Mockito.verify(response).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String body = new String(out.bytes(), StandardCharsets.UTF_8);
    assertThat(mapper.readTree(body).get("status").asInt()).isEqualTo(400);
    assertThat(mapper.readTree(body).get("title").asText()).isEqualTo("Bad request");
    assertThat(mapper.readTree(body).get("detail").asText()).isEqualTo("bad");
  }

  @Test
  void rejectsNullArguments() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThatThrownBy(() -> ProblemDetailWriter.write(null, problem))
        .isInstanceOf(NullPointerException.class);
    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    assertThatThrownBy(() -> ProblemDetailWriter.write(response, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void refusesToWriteWhenCommitted() throws Exception {
    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    Mockito.doReturn(HttpStatus.SERVICE_UNAVAILABLE.value()).when(response).getStatus();
    Mockito.doReturn(true).when(response).isCommitted();

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThatThrownBy(() -> ProblemDetailWriter.write(response, problem))
        .isInstanceOf(IllegalStateException.class);
  }
}
