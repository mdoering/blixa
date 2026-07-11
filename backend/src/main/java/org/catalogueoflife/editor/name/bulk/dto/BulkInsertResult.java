package org.catalogueoflife.editor.name.bulk.dto;

// Outcome of a bulk insert: `created` = usages created (accepted + synonyms), `synonymsLinked` =
// synonym->accepted links made, `targetId` echoes the anchor.
public record BulkInsertResult(int created, int synonymsLinked, int targetId) {}
