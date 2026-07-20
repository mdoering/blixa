package org.catalogueoflife.editor.project;

// One entry of project.favorite_clb_datasets (JSONB list, see V32__favorite_clb_datasets.sql /
// FavoriteClbDatasetListTypeHandler): a starred ChecklistBank dataset (its key + a display title)
// offered as a quick pick in the "compare with CLB" flow. Pure UX convenience; nothing enforces the
// key is a real dataset.
public record FavoriteClbDataset(String key, String title) {}
