package org.catalogueoflife.editor.name;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// Server-side "fetch a web page's <title>" for the References tab's "Add web URL" action -- the
// app itself makes this HTTP call on the caller's behalf, which is a textbook SSRF vector (an
// editor could otherwise coax the server into hitting internal-only services, e.g. a cloud
// metadata endpoint at a link-local address). Mirrors CrossrefClient's shape (static
// RestClient.builder(), connect/read timeouts, isolated behind this @Component so it can be
// @MockitoBean'd in ITs), but every guard below runs BEFORE any network call is made, and a
// fetch failure degrades to null rather than a 502 (see fetchTitle).
@Component
public class WebPageClient {

  // Title is always near the top of <head>; this just bounds how much of a huge/malicious
  // response body is ever buffered in memory while reading it off the wire (see readCapped).
  private static final int MAX_BODY_BYTES = 512 * 1024;
  private static final Pattern TITLE_PATTERN =
      Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final RestClient http;

  public WebPageClient() {
    // Redirects are NOT followed (see prepareConnection override below): the guard below only
    // validates the ORIGINAL url's resolved address, so silently following a 3xx to a
    // redirect-supplied Location would let a public, allowed url hop straight to an internal
    // address (e.g. a cloud metadata endpoint) with no re-check -- a classic SSRF-via-redirect
    // bypass. This feature doesn't need redirects: a redirecting page just degrades to
    // fetchTitle returning null, same as any other "couldn't get a title" case, and
    // addWebReference falls back to the raw url.
    var requestFactory = new SimpleClientHttpRequestFactory() {
      @Override
      protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
      }
    };
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder()
        .defaultHeader("User-Agent",
            "coldp-editor/0.1 (+https://github.com/CatalogueOfLife/coldp-editor)")
        .requestFactory(requestFactory)
        .build();
  }

  // Fetches `url` and returns its <title> (trimmed, HTML-entity-decoded), or null if the page has
  // none, the response isn't a success status (a redirect or error page's own title -- e.g. "404
  // Not Found" -- shouldn't become the reference's title), or the fetch fails. Rejects (400)
  // anything that isn't a plain http(s) URL resolving to a public address -- see requireAllowed,
  // which runs to completion before any fetch is attempted. A network failure/timeout AFTER that
  // point returns null rather than throwing: a broken title-fetch shouldn't block the caller from
  // adding the reference (NameUsageService.addWebReference falls back to the raw URL as the title).
  public String fetchTitle(String url) {
    URI uri = requireAllowed(url);
    String body;
    try {
      body = http.get().uri(uri).exchange((request, response) ->
          response.getStatusCode().is2xxSuccessful() ? readCapped(response.getBody()) : null);
    } catch (RuntimeException e) {
      return null;
    }
    return body == null ? null : extractTitle(body);
  }

  // The SSRF guard. Parses `url`, rejects non-http(s) schemes, then resolves the host and rejects
  // if ANY resolved address is loopback/link-local/site-local/any-local/multicast/IPv6-unique-local
  // -- e.g. a DNS name that resolves to 127.0.0.1 or 169.254.169.254 (cloud metadata) is rejected
  // just like a literal IP would be. This all happens before fetchTitle ever opens a connection
  // (and, per the constructor's redirect note above, is never bypassed by a followed redirect
  // either). Note: this check-then-connect shape has an inherent, accepted DNS-rebinding TOCTOU
  // gap -- the address validated here isn't pinned for the later connect, so a name with a very
  // short TTL could theoretically answer differently a moment later. Closing that fully would mean
  // connecting to a specific resolved IP while still presenting the right Host/SNI, which is out of
  // scope for this feature.
  private URI requireAllowed(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL not allowed");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL not allowed");
    }
    try {
      for (InetAddress addr : InetAddress.getAllByName(host)) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
            || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isIpv6UniqueLocal(addr)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL not allowed");
        }
      }
    } catch (UnknownHostException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL not allowed");
    }
    return uri;
  }

  // Inet6Address.isSiteLocalAddress() only implements the deprecated RFC 3879 fec0::/10 prefix,
  // NOT the modern RFC 4193 Unique Local Address range fc00::/7 (fc00:: - fdff:...) that real
  // internal IPv6 networks (Docker, k8s, corporate LANs) actually use today -- checked explicitly
  // here since none of the other InetAddress predicates cover it either.
  private static boolean isIpv6UniqueLocal(InetAddress addr) {
    if (!(addr instanceof Inet6Address)) {
      return false;
    }
    byte[] a = addr.getAddress();
    return (a[0] & 0xfe) == 0xfc; // top 7 bits == 1111110 -> fc00::/7
  }

  // Extracts and decodes the first <title>, or null if the body has none/it's blank -- split out
  // from fetchTitle (and package-private) so this pure string-processing logic is directly unit
  // testable without any network/Spring involvement (see WebPageClientTest).
  static String extractTitle(String body) {
    Matcher m = TITLE_PATTERN.matcher(body);
    if (!m.find()) {
      return null;
    }
    String title = decodeEntities(m.group(1).trim());
    return title.isEmpty() ? null : title;
  }

  // Reads at most MAX_BODY_BYTES off the response body stream (not the whole thing, however
  // large) and decodes it as UTF-8 -- caps actual bytes pulled off the wire rather than
  // truncating an already-fully-buffered string. Package-private for the same direct-unit-test
  // reason as extractTitle.
  static String readCapped(InputStream in) throws IOException {
    return new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
  }

  // &amp; is decoded LAST so a literal "&amp;lt;" in the source (representing the two characters
  // "&lt;" as text) round-trips to "&lt;", not "<".
  private static String decodeEntities(String s) {
    return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&");
  }
}
