package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

// See CreateNameUsageRequest for why the enum-bound fields stay tolerant Strings/List<String>.
public record UpdateNameUsageRequest(
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
    String etymology,
    String remarks,
    int version) {}
