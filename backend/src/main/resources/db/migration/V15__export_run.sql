-- One row per project ColDP export run (ExportRunService/ColdpWriter). A RUNNING row's file_path/
-- file_name/file_size/*_count columns are still their defaults; DONE fills them in (finished_at
-- set); FAILED sets only `error` (finished_at set, no file columns). Mirrors col_match_run's shape
-- (V12/V13__col_match_run*.sql) but for a single, whole-project background job instead of a
-- per-usage tallying one.
CREATE TABLE export_run (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id        BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  status            TEXT NOT NULL,          -- RUNNING | DONE | FAILED
  file_path         TEXT,                   -- internal on-disk path (ExportRunService.run's target); never exposed via the API
  file_name         TEXT,                   -- friendly download filename (Content-Disposition)
  file_size         BIGINT,
  name_usage_count  INTEGER NOT NULL DEFAULT 0,
  reference_count   INTEGER NOT NULL DEFAULT 0,
  started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at       TIMESTAMPTZ,
  error             TEXT
);
CREATE INDEX export_run_project_idx ON export_run (project_id, started_at DESC);

-- At most one RUNNING export_run per project. Backstops ExportRunService.start's
-- findRunningByProject pre-check (the friendly 409 path) at the DB level, race-proof against two
-- concurrent start() calls both passing the pre-check before either INSERT commits -- the second
-- INSERT then fails this partial unique index and is mapped to the same 409
-- (org.springframework.dao.DuplicateKeyException catch in ExportRunService.start).
CREATE UNIQUE INDEX export_run_active_idx ON export_run (project_id) WHERE status = 'RUNNING';
