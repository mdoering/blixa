-- Owner-toggled public visibility. `is_public` (not `public`, a reserved word).
ALTER TABLE project ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT false;

-- A release is an immutable, persisted ColDP snapshot of a project. Files live under
-- coldp.release.dir (separate from exports, never retention-swept). name_usage_count is a column
-- for the public list; the rest of the metrics snapshot is the `metrics` JSONB.
CREATE TABLE release (
  id               INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id       INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  version          TEXT NOT NULL,
  notes            TEXT,
  status           TEXT NOT NULL,                -- BUILDING | READY | FAILED
  name_usage_count INTEGER,
  metrics          JSONB,
  file_path        TEXT,
  file_name        TEXT,
  file_size        BIGINT,
  error            TEXT,
  created_by       INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX release_project_idx ON release (project_id, created_at DESC, id DESC);
