package org.catalogueoflife.editor.mergerecords.dto;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
// For preview: only `ids` is used. For merge: `survivorId` + `ids` (the full selected set incl. survivor).
public record MergeRequest(List<Integer> ids, Integer survivorId) {}
