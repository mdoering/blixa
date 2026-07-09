-- ColDP name/taxon child-entities. Each hangs off a name_usage via (project_id, usage_id) with an
-- ON DELETE CASCADE FK, and carries its own per-project id (compound PK, allocated from id_seq like
-- reference/name_usage). Vocab fields are TEXT (the UI supplies the ColDP vocab values); pointer
-- columns (reference_id, related_usage_id, area_id) are soft (app-validated where it matters).
-- name_relation + type_material apply to any usage; the other five to ACCEPTED usages only (enforced
-- in the service; NameUsageService.demote drops them when a taxon becomes a synonym).
-- Spec: docs/superpowers/specs/2026-07-09-name-taxon-child-entities-design.md

CREATE TABLE name_relation (
  project_id       INTEGER NOT NULL,
  id               INTEGER NOT NULL,
  usage_id         INTEGER NOT NULL,
  related_usage_id INTEGER,
  type             TEXT,
  reference_id     INTEGER,
  page             TEXT,
  remarks          TEXT,
  modified         TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by      INTEGER,
  version          INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX name_relation_usage_idx ON name_relation (project_id, usage_id);

CREATE TABLE type_material (
  project_id       INTEGER NOT NULL,
  id               INTEGER NOT NULL,
  usage_id         INTEGER NOT NULL,
  citation         TEXT,
  status           TEXT,
  institution_code TEXT,
  catalog_number   TEXT,
  occurrence_id    TEXT,
  locality         TEXT,
  country          TEXT,
  collector        TEXT,
  date             TEXT,
  sex              TEXT,
  reference_id     INTEGER,
  link             TEXT,
  remarks          TEXT,
  modified         TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by      INTEGER,
  version          INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX type_material_usage_idx ON type_material (project_id, usage_id);

CREATE TABLE vernacular (
  project_id   INTEGER NOT NULL,
  id           INTEGER NOT NULL,
  usage_id     INTEGER NOT NULL,
  name         TEXT,
  language     TEXT,
  country      TEXT,
  sex          TEXT,
  preferred    BOOLEAN,
  reference_id INTEGER,
  remarks      TEXT,
  modified     TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by  INTEGER,
  version      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX vernacular_usage_idx ON vernacular (project_id, usage_id);

CREATE TABLE distribution (
  project_id          INTEGER NOT NULL,
  id                  INTEGER NOT NULL,
  usage_id            INTEGER NOT NULL,
  area                TEXT,
  area_id             TEXT,
  gazetteer           TEXT,
  establishment_means TEXT,
  threat_status       TEXT,
  reference_id        INTEGER,
  remarks             TEXT,
  modified            TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by         INTEGER,
  version             INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX distribution_usage_idx ON distribution (project_id, usage_id);

CREATE TABLE media (
  project_id  INTEGER NOT NULL,
  id          INTEGER NOT NULL,
  usage_id    INTEGER NOT NULL,
  url         TEXT,
  type        TEXT,
  title       TEXT,
  creator     TEXT,
  license     TEXT,
  link        TEXT,
  remarks     TEXT,
  modified    TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by INTEGER,
  version     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX media_usage_idx ON media (project_id, usage_id);

CREATE TABLE estimate (
  project_id   INTEGER NOT NULL,
  id           INTEGER NOT NULL,
  usage_id     INTEGER NOT NULL,
  estimate     INTEGER,
  type         TEXT,
  reference_id INTEGER,
  remarks      TEXT,
  modified     TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by  INTEGER,
  version      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX estimate_usage_idx ON estimate (project_id, usage_id);

CREATE TABLE property (
  project_id   INTEGER NOT NULL,
  id           INTEGER NOT NULL,
  usage_id     INTEGER NOT NULL,
  property     TEXT,
  value        TEXT,
  page         TEXT,
  reference_id INTEGER,
  remarks      TEXT,
  modified     TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by  INTEGER,
  version      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  FOREIGN KEY (project_id, usage_id) REFERENCES name_usage (project_id, id) ON DELETE CASCADE
);
CREATE INDEX property_usage_idx ON property (project_id, usage_id);
