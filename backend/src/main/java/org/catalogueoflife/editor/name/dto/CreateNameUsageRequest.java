package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateNameUsageRequest(
    @NotBlank String scientificName,
    String authorship,
    @NotBlank String rank,
    @NotBlank String status,
    Long parentId,
    String namePhrase,
    String nomStatus,
    Long publishedInReferenceId,
    String publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    Boolean extinct,
    String link,
    String remarks) {}
