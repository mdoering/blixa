package org.catalogueoflife.editor.clb;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Resolves an opaque CLB {@code datasetKey} to a short, human-readable label (its alias, else its
 * title) for display anywhere the app shows a dataset key -- identifier scopes, favorite datasets,
 * "Compare with CLB", etc.
 *
 * <p>Backed by a small in-memory cache: a project only ever references a handful of CLB datasets and
 * their alias/title change rarely, so a plain map that never evicts is enough (no TTL/size bound
 * needed at this scale). Uses the lightweight per-dataset lookup (ClbImportClient.datasetLabel reads
 * just alias+title), not the full dataset details. A failed lookup is NOT cached and falls back to
 * the key itself, so a transient CLB outage self-heals on the next request.
 */
@Service
public class ClbDatasetLabelService {

  private final ClbImportClient client;
  private final Map<String, String> cache = new ConcurrentHashMap<>();

  public ClbDatasetLabelService(ClbImportClient client) {
    this.client = client;
  }

  /** The dataset's label (alias, else title); falls back to {@code key} itself if unresolvable. */
  public String label(String key) {
    if (key == null || key.isBlank()) {
      return key;
    }
    String cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    String label;
    try {
      label = client.datasetLabel(key);
    } catch (RuntimeException e) {
      label = null; // CLB unavailable / unknown key -- fall back to the key, do not cache
    }
    if (label != null && !label.isBlank()) {
      cache.put(key, label);
      return label;
    }
    return key;
  }

  /** Resolve several keys at once (deduped, insertion order preserved). */
  public Map<String, String> labels(Collection<String> keys) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String k : keys) {
      if (k != null && !k.isBlank() && !out.containsKey(k)) {
        out.put(k, label(k));
      }
    }
    return out;
  }
}
