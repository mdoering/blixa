CREATE TABLE change (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  entity_type TEXT NOT NULL,
  entity_id   INTEGER NOT NULL,
  operation   TEXT NOT NULL,           -- CREATE | UPDATE | DELETE
  diff        JSONB
);
CREATE INDEX change_project_at_idx ON change (project_id, at DESC, id DESC);
CREATE INDEX change_entity_idx     ON change (project_id, entity_type, entity_id, at DESC);
