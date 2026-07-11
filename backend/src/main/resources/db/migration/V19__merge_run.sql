-- merge_run mirrors import_run/export_run (V15/V18): a single async job row per supervised
-- project-merge run, but with a longer status lifecycle (RUNNING -> PLANNED -> APPLYING ->
-- DONE|FAILED) since compute-plan and apply are two separate phases the curator reviews between.
-- Unlike import (which creates a project) or export (a single project), merge reads two existing
-- projects: source_project_id is what's merged IN, target_project_id is what's merged INTO and
-- reconciled. plan/metrics/issues are JSONB blobs: plan holds {references:[Candidate],
-- names:[Candidate]} (MergePlan) computed once and editable via overrides; metrics is a
-- MergeMetrics snapshot recomputed on every plan change; issues is an array of per-record
-- non-fatal problems (MergeRunResponse.MergeIssue), filled once apply finishes.
CREATE TABLE merge_run (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           INTEGER NOT NULL REFERENCES app_user(id),
  source_project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  target_project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  status            TEXT NOT NULL,          -- RUNNING | PLANNED | APPLYING | DONE | FAILED
  mode              TEXT,                   -- null until apply: OVERWRITE|FILL_GAPS|NEW_ONLY
  transactional     BOOLEAN,                -- null until apply
  plan              JSONB,                  -- {references:[Candidate], names:[Candidate]}
  metrics           JSONB,
  issues            JSONB,
  started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  planned_at        TIMESTAMPTZ,
  finished_at       TIMESTAMPTZ,
  error             TEXT
);
CREATE INDEX merge_run_target_idx ON merge_run (target_project_id, started_at DESC);

-- One active run per TARGET project (RUNNING plan-compute OR APPLYING): backstops
-- MergeService.start's findActiveByTarget pre-check (the friendly 409 path) at the DB level,
-- race-proof against two concurrent start() calls both passing the pre-check before either INSERT
-- commits -- the second INSERT then fails this partial unique index. Scoped to the target only --
-- the same project can be the SOURCE of several concurrent merges into different targets, and a
-- PLANNED (reviewable, not yet applying) run does not hold the lock, only RUNNING/APPLYING do.
CREATE UNIQUE INDEX merge_run_active_idx ON merge_run (target_project_id)
  WHERE status IN ('RUNNING','APPLYING');
