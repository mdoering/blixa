package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// Plain unit test against a throwaway local HTTP server (the client only takes a base URL): checks
// what actually goes over the wire -- a multi-word name must be encoded exactly once (%20), not
// double-encoded (%2520), or CLB matches the literal "Homo%20sapiens" and finds nothing.
class ClbMatchClientTest {

  @Test
  void multiWordNameIsEncodedOnlyOnce() throws Exception {
    AtomicReference<String> rawQuery = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      rawQuery.set(ex.getRequestURI().getRawQuery());
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      ClbMatchClient client = new ClbMatchClient(JsonMapper.builder().build(),
          "http://127.0.0.1:" + server.getAddress().getPort(), "3LXR");
      client.match("3LXR", "Homo neanderthalensis", "King, 1864", "species", null, List.of());
    } finally {
      server.stop(0);
    }
    assertThat(rawQuery.get())
        .contains("scientificName=Homo%20neanderthalensis")
        .contains("authorship=King,%201864")
        .doesNotContain("%25");
  }
}
