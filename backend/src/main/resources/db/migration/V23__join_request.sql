CREATE TABLE join_request (
  id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  orcid TEXT NOT NULL,
  name TEXT,
  message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, orcid)
);
CREATE INDEX join_request_project_idx ON join_request (project_id);
