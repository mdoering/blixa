CREATE TABLE issue (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_id   INTEGER NOT NULL,
  rule        TEXT NOT NULL,
  severity    TEXT NOT NULL,        -- INFO | WARNING | ERROR
  message     TEXT NOT NULL,
  context     JSONB,
  status      TEXT NOT NULL DEFAULT 'OPEN',   -- OPEN | ACCEPTED | REJECTED | DONE
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewer_id INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  reviewed_at TIMESTAMPTZ,
  UNIQUE (project_id, entity_type, entity_id, rule)
);
CREATE INDEX issue_project_status_idx ON issue (project_id, status, severity);
CREATE INDEX issue_entity_idx ON issue (project_id, entity_type, entity_id);
