package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

// Descriptive fields bound to enums/data-driven types on the NameUsage model (status, nomStatus,
// gender, environment, temporalRangeStart/End) stay tolerant Strings/List<String> here -- the
// service parses them via VocabParsing, turning an unrecognized value into a 400 rather than a
// deserialization failure. publishedInYear is naturally numeric so it's an Integer end-to-end.
public record CreateNameUsageRequest(
    @NotBlank String scientificName,
    String authorship,
    @NotBlank String rank,
    @NotBlank String status,
    Integer parentId,
    String namePhrase,
    String nomStatus,
    Integer publishedInReferenceId,
    Integer publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    String gender,
    Boolean extinct,
    List<String> environment,
    String temporalRangeStart,
    String temporalRangeEnd,
    String remarks) {}
