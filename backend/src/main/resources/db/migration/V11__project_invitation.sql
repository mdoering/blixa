-- Owner-issued, emailed invitations to join a project. The token is the credential: whoever opens
-- the link and signs in may accept (single-use, expires_at bounds it). Kept after acceptance for the
-- record; revoking a pending invitation deletes the row.
CREATE TABLE project_invitation (
  id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  email       text NOT NULL,
  role        text NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
  message     text,
  token       text NOT NULL UNIQUE,
  invited_by  bigint REFERENCES app_user(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  accepted_at timestamptz,
  accepted_by bigint REFERENCES app_user(id) ON DELETE SET NULL
);

CREATE INDEX project_invitation_project_idx ON project_invitation (project_id);
