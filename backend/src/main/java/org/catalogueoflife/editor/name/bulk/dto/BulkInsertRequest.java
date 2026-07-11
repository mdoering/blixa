package org.catalogueoflife.editor.name.bulk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// One bulk request, shared by /usages/bulk/preview and /usages/bulk. `mode` is "children" or
// "synonyms" (parsed tolerantly server-side). `text` is a GBIF text-tree (a plain name list is
// the degenerate all-roots case).
public record BulkInsertRequest(
    @NotNull Integer targetId,
    @NotBlank String mode,
    @NotBlank String text) {}
