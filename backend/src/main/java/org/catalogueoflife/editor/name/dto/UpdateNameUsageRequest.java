package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateNameUsageRequest(
    @NotBlank String scientificName,
    String authorship,
    @NotBlank String rank,
    @NotBlank String status,
    Integer parentId,
    String namePhrase,
    String nomStatus,
    Integer publishedInReferenceId,
    String publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    Boolean extinct,
    String link,
    String remarks,
    int version) {}
