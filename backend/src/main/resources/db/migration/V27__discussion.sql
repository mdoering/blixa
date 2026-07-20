-- Discussions: per-project, ORCID-keyed forum-style conversation threads. Distinct from the
-- validation `issue` table (rule findings) -- see docs/superpowers/specs/2026-07-20-discussions-design.md.
-- Compound (project_id, id) PK with an app-allocated per-project id (id_seq / IdSeqMapper), same
-- pattern as reference/name_usage, so discussions read as #1, #2, ... within a project.
CREATE TABLE discussion (
  project_id    INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  id            INTEGER NOT NULL,
  title         TEXT NOT NULL,
  body          TEXT,                                   -- markdown
  status        TEXT NOT NULL DEFAULT 'OPEN',           -- REVIEW | OPEN | REJECTED | RESOLVED
  visibility    TEXT NOT NULL DEFAULT 'INTERNAL',       -- INTERNAL | PUBLIC (changing it is Phase 3)
  author_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,  -- null for Phase-4 API submissions
  author_orcid  TEXT,                                   -- denormalized display key
  created_via   TEXT NOT NULL DEFAULT 'UI',             -- UI | API
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  version       INTEGER NOT NULL DEFAULT 0,             -- optimistic locking (reference convention)
  PRIMARY KEY (project_id, id)
);

-- Full-text search over title + body (native pg FTS, same approach as reference_citation_fts / V26).
CREATE INDEX discussion_fts ON discussion
  USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(body, '')));
CREATE INDEX discussion_project_status_idx ON discussion (project_id, status);
CREATE INDEX discussion_project_author_idx ON discussion (project_id, author_id);
