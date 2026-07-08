CREATE TABLE task (
  id          INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
  title       TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'OPEN',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at   TIMESTAMPTZ
);
CREATE INDEX task_project_idx ON task (project_id, status);

ALTER TABLE change ADD COLUMN task_id INTEGER REFERENCES task(id) ON DELETE SET NULL;
CREATE INDEX change_task_idx ON change (project_id, task_id);
ALTER TABLE lock ADD COLUMN task_id INTEGER REFERENCES task(id) ON DELETE SET NULL;
