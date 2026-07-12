-- Reference authors/editors become structured CSL name arrays (see life.catalogue.api.model.CslName
-- / org.catalogueoflife.editor.name.CslNameListTypeHandler) instead of a "; "-joined free-text
-- string: `author`/`editor` are now JSONB arrays of {family, given, droppingParticle,
-- nonDroppingParticle, suffix, isInstitution, orcid, literal} objects.
--
-- `container_title_short` adds an abbreviated container title (CSL container-title-short, e.g. a
-- botanical journal abbreviation) alongside the existing `container_title`.
--
-- `citation_manual` distinguishes a hand-typed citation (kept as-is) from one generated from the
-- structured fields (a later task in the reference-model-overhaul plan wires the actual generation;
-- for now every reference behaves like today -- callers set `citation` themselves).
--
-- No backfill: the app has no production data yet (fresh installs only -- see the plan's Global
-- Constraints), so this drops + recreates the columns rather than an ALTER COLUMN ... TYPE ... USING.
ALTER TABLE reference DROP COLUMN author;
ALTER TABLE reference DROP COLUMN editor;
ALTER TABLE reference ADD COLUMN author JSONB;
ALTER TABLE reference ADD COLUMN editor JSONB;
ALTER TABLE reference ADD COLUMN container_title_short TEXT;
ALTER TABLE reference ADD COLUMN citation_manual BOOLEAN NOT NULL DEFAULT false;
