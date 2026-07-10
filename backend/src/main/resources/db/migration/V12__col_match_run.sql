-- One row per project-wide bulk COL-match run (ColMatchJobService.runSync). Progress/tallies are
-- updated in place as the run proceeds (ColMatchRunMapper.tick) so a still-RUNNING row is a live
-- progress indicator; DONE/FAILED are terminal (finished_at set, error only on FAILED).
CREATE TABLE col_match_run (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  status      TEXT NOT NULL,          -- RUNNING | DONE | FAILED
  total       INTEGER NOT NULL DEFAULT 0,
  processed   INTEGER NOT NULL DEFAULT 0,
  verified    INTEGER NOT NULL DEFAULT 0,
  added       INTEGER NOT NULL DEFAULT 0,
  updated     INTEGER NOT NULL DEFAULT 0,
  unmatched   INTEGER NOT NULL DEFAULT 0,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  error       TEXT
);
CREATE INDEX col_match_run_project_idx ON col_match_run (project_id, started_at DESC);
