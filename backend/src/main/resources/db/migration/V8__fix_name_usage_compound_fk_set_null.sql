-- Fixes a schema bug in V3__name_core.sql's compound self/cross FKs on name_usage
-- (parent_id/basionym_id/published_in_reference_id): Postgres's ON DELETE SET NULL, when given NO
-- column list, nulls EVERY column of the FK -- including project_id, which is NOT NULL -- not just
-- the single column that actually referenced the deleted row. Column-list SET NULL
-- (`ON DELETE SET NULL (col)`) has been available since Postgres 15 (this deployment targets
-- Postgres 17) and is exactly what these three FKs need: only the column pointing at the deleted
-- row should be cleared, project_id must never be touched. Before this fix, deleting a usage with
-- children or basionym descendants, or a reference cited by any usage (see
-- name/ReferenceService.delete), failed with a NOT NULL violation on project_id instead of quietly
-- nulling parent_id/basionym_id/published_in_reference_id as the FK was meant to.
ALTER TABLE name_usage DROP CONSTRAINT name_usage_project_id_parent_id_fkey;
ALTER TABLE name_usage ADD CONSTRAINT name_usage_project_id_parent_id_fkey
  FOREIGN KEY (project_id, parent_id) REFERENCES name_usage(project_id, id) ON DELETE SET NULL (parent_id);

ALTER TABLE name_usage DROP CONSTRAINT name_usage_project_id_basionym_id_fkey;
ALTER TABLE name_usage ADD CONSTRAINT name_usage_project_id_basionym_id_fkey
  FOREIGN KEY (project_id, basionym_id) REFERENCES name_usage(project_id, id) ON DELETE SET NULL (basionym_id);

ALTER TABLE name_usage DROP CONSTRAINT name_usage_project_id_published_in_reference_id_fkey;
ALTER TABLE name_usage ADD CONSTRAINT name_usage_project_id_published_in_reference_id_fkey
  FOREIGN KEY (project_id, published_in_reference_id) REFERENCES reference(project_id, id)
  ON DELETE SET NULL (published_in_reference_id);
