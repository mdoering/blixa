-- Phase 4 of Discussions: a per-project API token that gates external issue submission
-- (POST /api/public/projects/{pid}/discussions). One token per project; regenerating replaces it.
CREATE TABLE discussion_api_token (
  project_id INTEGER PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
  token      TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
