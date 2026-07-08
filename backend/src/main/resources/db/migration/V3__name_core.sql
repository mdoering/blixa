CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Per-(project, entity) allocation counter backing the app-allocated compound
-- (project_id, id) primary keys below. Each project gets its own independent
-- 1,2,3,... sequence per entity (e.g. "reference", "name_usage").
CREATE TABLE id_seq (
  project_id INTEGER NOT NULL,
  entity     TEXT NOT NULL,
  next_id    INTEGER NOT NULL,
  PRIMARY KEY (project_id, entity)
);

CREATE TABLE reference (
  id             INTEGER NOT NULL,               -- app-allocated via id_seq, NOT identity
  project_id     INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,                       -- original ColDP Reference.ID (for round-trip)
  alternative_id TEXT[],
  citation       TEXT,
  type           TEXT,                       -- CSL type
  author         TEXT,
  editor         TEXT,
  title          TEXT,
  container_title TEXT,
  issued         TEXT,
  volume         TEXT,
  issue          TEXT,
  page           TEXT,
  publisher      TEXT,
  doi            TEXT,
  isbn           TEXT,
  issn           TEXT,
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX reference_citation_trgm ON reference USING gin (citation gin_trgm_ops);

CREATE TABLE author (
  id             INTEGER NOT NULL,               -- app-allocated via id_seq, NOT identity
  project_id     INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,
  alternative_id TEXT[],
  given          TEXT,
  family         TEXT,
  suffix         TEXT,
  abbreviation_botany TEXT,
  affiliation    TEXT,
  birth          TEXT,
  death          TEXT,
  birth_place    TEXT,
  country        TEXT,
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  UNIQUE (project_id, coldp_id)
);

CREATE TABLE name_usage (
  id             INTEGER NOT NULL,               -- app-allocated via id_seq, NOT identity
  project_id     INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,
  alternative_id TEXT[],
  parent_id      INTEGER,                        -- accepted classification tree only (self, same project)
  basionym_id    INTEGER,                        -- self, same project
  ordinal        INTEGER,
  -- taxonomic
  status         TEXT NOT NULL,             -- Status enum name (org.catalogueoflife.editor.name.Status)
  name_phrase    TEXT,
  reference_id   INTEGER[],                 -- taxonomic references (same project; no array FK in Postgres)
  extinct        BOOLEAN,
  environment    TEXT[],                    -- life.catalogue.api.vocab.Environment enum names
  temporal_range_start TEXT,                -- life.catalogue.api.vocab.GeoTime name, e.g. 'Jurassic'
  temporal_range_end   TEXT,                -- life.catalogue.api.vocab.GeoTime name
  -- nomenclatural (name)
  scientific_name TEXT NOT NULL,
  authorship     TEXT,
  rank           TEXT NOT NULL,             -- Rank enum name
  uninomial      TEXT,
  genus          TEXT,
  infrageneric_epithet TEXT,
  specific_epithet     TEXT,
  infraspecific_epithet TEXT,
  cultivar_epithet TEXT,
  notho          TEXT,                      -- org.gbif.nameparser.api.NamePart enum name
  combination_authorship TEXT,
  combination_ex_authorship TEXT,
  combination_authorship_year TEXT,
  basionym_authorship TEXT,
  basionym_ex_authorship TEXT,
  basionym_authorship_year TEXT,
  sanctioning_author TEXT,
  nom_status     TEXT,                      -- life.catalogue.api.vocab.NomStatus enum name
  published_in_reference_id INTEGER,        -- reference, same project
  published_in_year INTEGER,
  published_in_page TEXT,
  published_in_page_link TEXT,
  gender         TEXT,                      -- life.catalogue.api.vocab.Gender enum name
  etymology      TEXT,
  name_type      TEXT,                      -- NameType from the parser (e.g. SCIENTIFIC, VIRUS, PLACEHOLDER)
  parse_state    TEXT,                      -- ParsedName.State (COMPLETE/PARTIAL/...) or 'UNPARSABLE'
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, id),
  UNIQUE (project_id, coldp_id),
  -- Compound self/cross FKs: the DB enforces that parent/basionym/published-in stay within the
  -- SAME project as the referencing row. Postgres MATCH SIMPLE (the default) skips the FK check
  -- whenever any referencing column is NULL, so roots (parent_id/basionym_id NULL) are unaffected.
  FOREIGN KEY (project_id, parent_id)                REFERENCES name_usage(project_id, id) ON DELETE SET NULL,
  FOREIGN KEY (project_id, basionym_id)              REFERENCES name_usage(project_id, id) ON DELETE SET NULL,
  FOREIGN KEY (project_id, published_in_reference_id) REFERENCES reference(project_id, id) ON DELETE SET NULL
);
CREATE INDEX name_usage_parent_idx ON name_usage (project_id, parent_id);
CREATE INDEX name_usage_sciname_trgm ON name_usage USING gin (scientific_name gin_trgm_ops);

CREATE TABLE synonym_accepted (
  project_id  INTEGER NOT NULL,
  synonym_id  INTEGER NOT NULL,
  accepted_id INTEGER NOT NULL,
  ordinal     INTEGER,
  PRIMARY KEY (project_id, synonym_id, accepted_id),
  FOREIGN KEY (project_id, synonym_id)  REFERENCES name_usage(project_id, id) ON DELETE CASCADE,
  FOREIGN KEY (project_id, accepted_id) REFERENCES name_usage(project_id, id) ON DELETE CASCADE
);
CREATE INDEX synonym_accepted_accepted_idx ON synonym_accepted (project_id, accepted_id);
