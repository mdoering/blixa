package org.catalogueoflife.editor.project.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.catalogueoflife.editor.project.FavoriteClbDataset;
import org.catalogueoflife.editor.project.IdentifierScope;

public record UpdateProjectMetadataRequest(
    @NotBlank String title,
    String alias,
    String description,
    String nomCode,
    String license,
    String geographicScope,
    String taxonomicScope,
    Boolean gbifOccurrenceLayer,
    // Which alternative_id CURIE scopes (e.g. "ipni", "gbif") the Details form renders a real
    // identifier field for, each with an optional CLB dataset key (matchable iff datasetKey is
    // set). Same "omitted -> keep existing" contract as gbifOccurrenceLayer (see
    // ProjectService.updateMetadata); an explicit [] clears the configured scopes.
    List<IdentifierScope> identifierScopes,
    // Starred ChecklistBank datasets (key + title) for the compare-with-CLB quick picks. Same
    // "omitted -> keep existing" contract as identifierScopes; an explicit [] clears them.
    List<FavoriteClbDataset> favoriteClbDatasets,
    // Which CSL style (e.g. "apa", "harvard" -- life.catalogue.common.csl.CslFormatter.STYLE,
    // case-insensitive) generated reference citations should render in. Same "omitted -> keep
    // existing" contract as gbifOccurrenceLayer/identifierScopes above; a non-null value is
    // validated against the STYLE set by ProjectService.updateMetadata (400 if unrecognized), and a
    // value that actually changes the project's stored style regenerates every non-manual
    // reference's citation.
    String cslStyle) {}
