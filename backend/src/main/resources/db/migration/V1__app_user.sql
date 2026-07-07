CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  orcid         TEXT UNIQUE,
  username      TEXT UNIQUE NOT NULL,
  email         TEXT,
  display_name  TEXT,
  given         TEXT,
  family        TEXT,
  password_hash TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
