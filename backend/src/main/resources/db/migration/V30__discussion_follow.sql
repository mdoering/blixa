-- Phase 5 of Discussions: per-user follow ("heart") for change notifications.
CREATE TABLE discussion_follow (
  project_id    INTEGER NOT NULL,
  discussion_id INTEGER NOT NULL,
  user_id       INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, discussion_id, user_id),
  FOREIGN KEY (project_id, discussion_id) REFERENCES discussion(project_id, id) ON DELETE CASCADE
);
CREATE INDEX discussion_follow_by_discussion_idx ON discussion_follow (project_id, discussion_id);
