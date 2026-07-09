-- Taxon-level attributes (extinct, environment, temporal range) apply only to ACCEPTED usages, so
-- they live in their own table keyed by the usage rather than on the shared name_usage row. This
-- lets a status change (accepted <-> synonym) re-key or drop one row instead of shuffling columns,
-- and keeps name_usage focused on name/nomenclatural fields.
-- Spec: docs/superpowers/specs/2026-07-09-taxon-info-refactor-design.md
CREATE TABLE taxon_info (
  project_id           INTEGER NOT NULL,
  usage_id             INTEGER NOT NULL,
  extinct              BOOLEAN,
  environment          TEXT[],            -- life.catalogue.api.vocab.Environment enum names
  temporal_range_start TEXT,
  temporal_range_end   TEXT,
  PRIMARY KEY (project_id, usage_id),
  FOREIGN KEY (project_id, usage_id)
      REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);

-- Preserve existing taxon info for ACCEPTED usages only (the invariant going forward). In practice
-- there is none yet, so this copies nothing; kept for correctness.
INSERT INTO taxon_info (project_id, usage_id, extinct, environment,
                        temporal_range_start, temporal_range_end)
SELECT project_id, id, extinct, environment, temporal_range_start, temporal_range_end
FROM name_usage
WHERE status = 'ACCEPTED'
  AND (extinct IS NOT NULL OR environment IS NOT NULL
       OR temporal_range_start IS NOT NULL OR temporal_range_end IS NOT NULL);

ALTER TABLE name_usage
  DROP COLUMN extinct,
  DROP COLUMN environment,
  DROP COLUMN temporal_range_start,
  DROP COLUMN temporal_range_end;
