package org.catalogueoflife.editor.name.bulk.dto;

import java.util.List;

// Structured preview: `valid` gates the confirm button; on a parse error `valid=false` + `error`
// carries the library's line-numbered message. Counts drive the summary; `nodes` renders the tree.
public record BulkPreviewResponse(
    boolean valid,
    String error,
    int total,
    int accepted,
    int synonyms,
    int duplicates,
    List<PreviewNode> nodes) {

  public record PreviewNode(
      String name,
      String rank,
      String status,
      boolean extinct,
      boolean duplicate,
      List<PreviewNode> children,
      List<PreviewNode> synonyms) {}

  public static BulkPreviewResponse invalid(String error) {
    return new BulkPreviewResponse(false, error, 0, 0, 0, 0, List.of());
  }
}
