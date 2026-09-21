package org.catalogueoflife.editor.clb;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * CLB vocabularies the UI renders codes with: ISO 639-3 language names (a vernacular's "nld" reads
 * "Dutch") and ISO 3166 country names ("DE" / "DEU" read "Germany"). Each is fetched from CLB once
 * and held for the life of the process (they change with ISO releases, not day to day); a failed
 * fetch is not cached, so it retries on the next request.
 */
@Service
public class ClbVocabService {

  private final ClbImportClient client;
  private volatile Map<String, String> languages;
  private volatile Map<String, String> countries;

  public ClbVocabService(ClbImportClient client) {
    this.client = client;
  }

  public Map<String, String> languages() {
    Map<String, String> l = languages;
    if (l == null) {
      synchronized (this) {
        if (languages == null) languages = load(client::languages);
        l = languages;
      }
    }
    return l;
  }

  public Map<String, String> countries() {
    Map<String, String> c = countries;
    if (c == null) {
      synchronized (this) {
        if (countries == null) countries = load(client::countries);
        c = countries;
      }
    }
    return c;
  }

  private static Map<String, String> load(Supplier<Map<String, String>> fetch) {
    return Map.copyOf(fetch.get());
  }
}
