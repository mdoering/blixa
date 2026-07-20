-- Phase 6 of Discussions: link a discussion to the changelog entries (audit changes) that resolved
-- or relate to it. Each change also carries its task_id, so a linked change references the work
-- session (and its lock) too.
CREATE TABLE discussion_change (
  project_id    INTEGER NOT NULL,
  discussion_id INTEGER NOT NULL,
  change_id     INTEGER NOT NULL REFERENCES change(id) ON DELETE CASCADE,
  PRIMARY KEY (project_id, discussion_id, change_id),
  FOREIGN KEY (project_id, discussion_id) REFERENCES discussion(project_id, id) ON DELETE CASCADE
);
CREATE INDEX discussion_change_by_change_idx ON discussion_change (change_id);
