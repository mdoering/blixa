-- import_run mirrors export_run (V15): a single async job row per import, RUNNING -> DONE|FAILED,
-- swept stale on startup. Unlike export, import CREATES the project, so project_id is nullable and
-- set once the job has created it. issues is a JSONB array of non-fatal per-row problems.
CREATE TABLE import_run (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           INTEGER NOT NULL REFERENCES app_user(id),
  project_id        BIGINT REFERENCES project(id) ON DELETE CASCADE,
  status            TEXT NOT NULL,               -- RUNNING | DONE | FAILED
  source_name       TEXT,                        -- uploaded filename
  preserve_ids      BOOLEAN NOT NULL DEFAULT false,
  id_scope          TEXT,                        -- scope prefix for preserved source ids (iff preserve_ids)
  name_usage_count  INTEGER NOT NULL DEFAULT 0,
  reference_count   INTEGER NOT NULL DEFAULT 0,
  author_count      INTEGER NOT NULL DEFAULT 0,
  issues            JSONB,                       -- array of {entity, sourceId, message}
  started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at       TIMESTAMPTZ,
  error             TEXT
);
CREATE INDEX import_run_user_idx ON import_run (user_id, started_at DESC);

-- No partial-unique active index (unlike export_run_active_idx): import has no existing project to
-- guard concurrent runs against, and ImportAsyncConfig's single-thread executor already serializes
-- every import job one-at-a-time, so a second concurrent RUNNING row for the same user can't happen
-- in practice.
