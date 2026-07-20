-- project.favorite_clb_datasets: JSONB list of {key, title} objects (see FavoriteClbDataset /
-- FavoriteClbDatasetListTypeHandler) -- starred ChecklistBank datasets offered as quick picks in the
-- "compare with CLB" flow. Nullable; managed via the project metadata update (owner/editor).
ALTER TABLE project ADD COLUMN favorite_clb_datasets JSONB;
