package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// SSRF guard unit tests for WebPageClient.fetchTitle -- pure/no Spring context, using the REAL
// client (not mocked), and asserting only the REJECT paths: every case here must fail via
// requireAllowed BEFORE any network call is attempted, so these run fine offline/sandboxed. A
// Spring IT must NEVER exercise the real client for a successful fetch -- see
// NameUsageReferencesIT, which @MockitoBean's WebPageClient instead.
class WebPageClientTest {

  private final WebPageClient client = new WebPageClient();

  @Test
  void rejectsLoopbackAddress() {
    assertRejectedBeforeAnyFetch("http://127.0.0.1/x");
  }

  @Test
  void rejectsLinkLocalCloudMetadataAddress() {
    assertRejectedBeforeAnyFetch("http://169.254.169.254/latest");
  }

  @Test
  void rejectsLocalhostHostname() {
    // "localhost" resolves to a loopback address via the hosts file (no real DNS lookup), same
    // guard as a literal 127.0.0.1 -- a hostname must not be a way around the IP-address guard.
    assertRejectedBeforeAnyFetch("http://localhost/x");
  }

  @Test
  void rejectsNonHttpScheme() {
    // "ftp://x" is rejected on the scheme check alone -- host "x" is never even resolved.
    assertRejectedBeforeAnyFetch("ftp://x");
  }

  @Test
  void rejectsAnUnparsableUrl() {
    assertRejectedBeforeAnyFetch("not a url");
  }

  private void assertRejectedBeforeAnyFetch(String url) {
    assertThatThrownBy(() -> client.fetchTitle(url))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // extractTitle/readCapped are the pure string/stream-processing halves of fetchTitle (split out
  // so they're directly testable without a real HTTP fetch -- see WebPageClient). These cover the
  // parsing behavior the SSRF-reject tests above can't reach, since every case there is rejected
  // before any body is ever read.

  @Test
  void extractsASimpleTitle() {
    assertThat(WebPageClient.extractTitle("<html><head><title>Example Page</title></head></html>"))
        .isEqualTo("Example Page");
  }

  @Test
  void extractIsCaseInsensitiveAndHandlesAttributesOnTheTag() {
    assertThat(WebPageClient.extractTitle("<TITLE class=\"x\">Upper Case Tag</TITLE>"))
        .isEqualTo("Upper Case Tag");
  }

  @Test
  void extractTrimsWhitespaceAndSpansMultipleLines() {
    assertThat(WebPageClient.extractTitle("<title>\n  Multi\n  Line\n</title>"))
        .isEqualTo("Multi\n  Line");
  }

  @Test
  void extractDecodesHtmlEntitiesInOrderSoDoubleEncodedAmpDoesNotOverDecode() {
    assertThat(WebPageClient.extractTitle("<title>Fish &amp; Chips</title>")).isEqualTo("Fish & Chips");
    // A literal "&lt;" in the source text (i.e. &amp;lt;) must round-trip to "&lt;", not "<".
    assertThat(WebPageClient.extractTitle("<title>literal &amp;lt; text</title>"))
        .isEqualTo("literal &lt; text");
  }

  @Test
  void extractReturnsNullWhenThereIsNoTitleTag() {
    assertThat(WebPageClient.extractTitle("<html><body>no title here</body></html>")).isNull();
  }

  @Test
  void extractReturnsNullForABlankTitle() {
    assertThat(WebPageClient.extractTitle("<title>   </title>")).isNull();
  }

  @Test
  void readCappedReadsTheWholeStreamWhenUnderTheCap() throws Exception {
    String text = "hello world";
    String result = WebPageClient.readCapped(
        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    assertThat(result).isEqualTo(text);
  }

  @Test
  void readCappedStopsAtTheByteCapInsteadOfBufferingAnUnboundedBody() throws Exception {
    // One byte over the 512 KB cap -- the result must be capped, not the full input.
    byte[] huge = new byte[512 * 1024 + 1];
    java.util.Arrays.fill(huge, (byte) 'x');
    String result = WebPageClient.readCapped(new ByteArrayInputStream(huge));
    assertThat(result).hasSize(512 * 1024);
  }
}
