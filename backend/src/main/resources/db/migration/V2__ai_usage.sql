-- Per-project AI usage log: one row per "gather suggestions" run, so the operator can see AI
-- consumption per project (the operator's configured provider account pays). Records token counts
-- only, never suggestion content. usage_id/user_id are deliberately un-FK'd so a row survives the
-- taxon or user being deleted later.
CREATE TABLE ai_usage (
  id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id    integer NOT NULL REFERENCES project (id) ON DELETE CASCADE,
  usage_id      integer,
  user_id       integer,
  provider      text NOT NULL,
  model         text NOT NULL,
  input_tokens  integer NOT NULL DEFAULT 0,
  output_tokens integer NOT NULL DEFAULT 0,
  created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ai_usage_project_idx ON ai_usage (project_id);
