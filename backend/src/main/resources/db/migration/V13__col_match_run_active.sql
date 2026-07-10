-- At most one RUNNING col_match_run per project. Backstops ColMatchJobService.start's
-- findRunningByProject pre-check (the friendly 409 path) at the DB level, race-proof against two
-- concurrent start() calls both passing the pre-check before either INSERT commits -- the second
-- INSERT then fails this partial unique index and is mapped to the same 409
-- (org.springframework.dao.DuplicateKeyException catch in ColMatchJobService.start).
CREATE UNIQUE INDEX col_match_run_active_idx ON col_match_run (project_id) WHERE status = 'RUNNING';
