package org.catalogueoflife.editor.name.dto;

// Record counts behind each TaxonDetail tab (GET /usages/{id}/counts), so the tab labels can show
// "Synonyms (3)" without loading every tab's list. `properties` backs the Biology tab. The references
// count is not here -- the client derives it from the usage's own referenceId list.
public record UsageCounts(int synonyms, int nameRelations, int typeMaterial, int vernaculars,
    int distributions, int media, int estimates, int properties, int issues, int discussions) {}
