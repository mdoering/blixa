CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE reference (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
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
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX reference_project_idx ON reference (project_id);
CREATE INDEX reference_citation_trgm ON reference USING gin (citation gin_trgm_ops);

CREATE TABLE author (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
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
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX author_project_idx ON author (project_id);

CREATE TABLE name_usage (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  coldp_id       TEXT,
  alternative_id TEXT[],
  parent_id      BIGINT REFERENCES name_usage(id) ON DELETE SET NULL,   -- accepted classification tree only
  basionym_id    BIGINT REFERENCES name_usage(id) ON DELETE SET NULL,
  ordinal        INTEGER,
  -- taxonomic
  status         TEXT NOT NULL,             -- TaxonomicStatus enum name
  name_phrase    TEXT,
  reference_id   BIGINT[],                  -- taxonomic references
  extinct        BOOLEAN,
  environment    TEXT[],
  temporal_range_start TEXT,
  temporal_range_end   TEXT,
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
  notho          TEXT,
  combination_authorship TEXT,
  combination_ex_authorship TEXT,
  combination_authorship_year TEXT,
  basionym_authorship TEXT,
  basionym_ex_authorship TEXT,
  basionym_authorship_year TEXT,
  sanctioning_author TEXT,
  nom_status     TEXT,                      -- NomStatus enum name
  published_in_reference_id BIGINT REFERENCES reference(id) ON DELETE SET NULL,
  published_in_year TEXT,
  published_in_page TEXT,
  published_in_page_link TEXT,
  gender         TEXT,
  etymology      TEXT,
  name_type      TEXT,                      -- NameType from the parser (e.g. SCIENTIFIC, VIRUS, PLACEHOLDER)
  parse_state    TEXT,                      -- ParsedName.State (COMPLETE/PARTIAL/...) or 'UNPARSABLE'
  link           TEXT,
  remarks        TEXT,
  modified       TIMESTAMPTZ NOT NULL DEFAULT now(),
  modified_by    BIGINT REFERENCES app_user(id),
  version        INTEGER NOT NULL DEFAULT 0,
  UNIQUE (project_id, coldp_id)
);
CREATE INDEX name_usage_project_idx ON name_usage (project_id);
CREATE INDEX name_usage_parent_idx ON name_usage (project_id, parent_id);
CREATE INDEX name_usage_sciname_trgm ON name_usage USING gin (scientific_name gin_trgm_ops);

CREATE TABLE synonym_accepted (
  synonym_usage_id  BIGINT NOT NULL REFERENCES name_usage(id) ON DELETE CASCADE,
  accepted_usage_id BIGINT NOT NULL REFERENCES name_usage(id) ON DELETE CASCADE,
  ordinal           INTEGER,
  PRIMARY KEY (synonym_usage_id, accepted_usage_id)
);
CREATE INDEX synonym_accepted_accepted_idx ON synonym_accepted (accepted_usage_id);
