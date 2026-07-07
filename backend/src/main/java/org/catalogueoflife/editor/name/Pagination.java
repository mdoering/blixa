package org.catalogueoflife.editor.name;

/**
 * Shared defense-in-depth pagination clamp for list/search endpoints. Query params are
 * client-controlled ints; without clamping, a negative {@code limit} (e.g. -1) is passed straight
 * through to a SQL {@code LIMIT}/{@code OFFSET} clause, which Postgres rejects with a 500 rather
 * than a well-behaved empty/clamped result.
 */
public final class Pagination {

  public static final int MAX_LIMIT = 200;

  private Pagination() {}

  /** Clamps {@code limit} to the inclusive range [1, {@value #MAX_LIMIT}]. */
  public static int clampLimit(int limit) {
    if (limit < 1) {
      return 1;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  /** Clamps {@code offset} to be non-negative. */
  public static int clampOffset(int offset) {
    return Math.max(offset, 0);
  }
}
