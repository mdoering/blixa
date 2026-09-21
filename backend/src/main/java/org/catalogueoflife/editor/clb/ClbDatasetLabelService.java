package org.catalogueoflife.editor.clb;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.catalogueoflife.editor.clb.ClbImportClient.ClbDatasetRef;
import org.springframework.stereotype.Service;

/**
 * Resolves an opaque CLB {@code datasetKey} to a short, human-readable label (its alias, else its
 * title) for display anywhere the app shows a dataset key -- identifier scopes, favorite datasets,
 * "Compare with CLB", etc. -- and tells whether CLB lets us read the dataset at all (a private one
 * is refused, yet still shows up in CLB's global name search).
 *
 * <p>Backed by a small in-memory cache: a project only ever references a handful of CLB datasets and
 * their alias/title change rarely, so a plain map that never evicts is enough (no TTL/size bound
 * needed at this scale). Uses the lightweight per-dataset lookup (ClbImportClient.dataset reads
 * just alias+title), not the full dataset details. Inaccessible datasets are cached too; a failed
 * lookup (CLB down) is NOT cached and falls back to the key itself, so an outage self-heals.
 */
@Service
public class ClbDatasetLabelService {

  private final ClbImportClient client;
  private final Map<String, ClbDatasetRef> cache = new ConcurrentHashMap<>();

  public ClbDatasetLabelService(ClbImportClient client) {
    this.client = client;
  }

  /** The cached-or-fetched dataset, or {@code null} when CLB couldn't be asked (not cached). */
  public ClbDatasetRef dataset(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    ClbDatasetRef cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    ClbDatasetRef ref;
    try {
      ref = client.dataset(key);
    } catch (RuntimeException e) {
      return null; // CLB unavailable -- do not cache
    }
    if (ref != null) {
      cache.put(key, ref);
    }
    return ref;
  }

  /** The dataset's label (alias, else title); falls back to {@code key} itself if unresolvable. */
  public String label(String key) {
    ClbDatasetRef ref = dataset(key);
    String label = ref == null ? null : ref.label();
    return label != null && !label.isBlank() ? label : key;
  }

  /**
   * Looks up several datasets concurrently (one virtual thread per uncached key), so a page of
   * search hits spanning many datasets costs one round-trip's latency rather than one per dataset.
   * Keys CLB couldn't be asked about are absent from the result.
   */
  public Map<String, ClbDatasetRef> datasets(Collection<String> keys) {
    Set<String> distinct = new LinkedHashSet<>();
    for (String k : keys) {
      if (k != null && !k.isBlank()) distinct.add(k);
    }
    Map<String, ClbDatasetRef> out = new LinkedHashMap<>();
    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, Future<ClbDatasetRef>> futures = new LinkedHashMap<>();
      for (String k : distinct) {
        futures.put(k, exec.submit(() -> dataset(k)));
      }
      for (var e : futures.entrySet()) {
        try {
          ClbDatasetRef ref = e.getValue().get();
          if (ref != null) out.put(e.getKey(), ref);
        } catch (ExecutionException ex) {
          // dataset() never throws; nothing to add
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return out;
        }
      }
    }
    return out;
  }

  /** Resolve several keys at once (deduped, insertion order preserved). */
  public Map<String, String> labels(Collection<String> keys) {
    Map<String, ClbDatasetRef> refs = datasets(keys);
    Map<String, String> out = new LinkedHashMap<>();
    for (String k : keys) {
      if (k != null && !k.isBlank() && !out.containsKey(k)) {
        ClbDatasetRef ref = refs.get(k);
        String label = ref == null ? null : ref.label();
        out.put(k, label != null && !label.isBlank() ? label : k);
      }
    }
    return out;
  }
}
