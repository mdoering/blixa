package org.catalogueoflife.editor.name.dto;

// One distinct container_title (journal name) value plus how many references cite it --
// ReferenceMapper.containerTitleFacet's row shape for the journal-name reconciliation UI
// (ReconcileJournalsModal), which lets an editor spot variant spellings of the same journal
// and normalize them to a single canonical value via ReferenceService.mergeContainerTitle.
public record ContainerTitleFacet(String value, int count) {}
