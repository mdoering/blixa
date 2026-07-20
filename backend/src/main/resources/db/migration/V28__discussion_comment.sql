-- Phase 2 of Discussions: threaded comments + name reverse-links.
-- discussion_comment: markdown comments under a discussion (per-project id via IdSeqMapper).
CREATE TABLE discussion_comment (
  project_id    INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  id            INTEGER NOT NULL,
  discussion_id INTEGER NOT NULL,
  body          TEXT NOT NULL,
  author_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  author_orcid  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  version       INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, discussion_id) REFERENCES discussion(project_id, id) ON DELETE CASCADE
);
CREATE INDEX discussion_comment_thread_idx ON discussion_comment (project_id, discussion_id, id);

-- discussion_usage: reverse links from a name_usage to the discussions that mention it (#nameID in
-- the discussion body or any of its comments). Reconciled on every discussion/comment write. The FK
-- to name_usage means only existing usages are ever linked (dangling #ids are skipped upstream).
CREATE TABLE discussion_usage (
  project_id    INTEGER NOT NULL,
  discussion_id INTEGER NOT NULL,
  usage_id      INTEGER NOT NULL,
  PRIMARY KEY (project_id, discussion_id, usage_id),
  FOREIGN KEY (project_id, discussion_id) REFERENCES discussion(project_id, id) ON DELETE CASCADE,
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage(project_id, id) ON DELETE CASCADE
);
CREATE INDEX discussion_usage_by_usage_idx ON discussion_usage (project_id, usage_id);
