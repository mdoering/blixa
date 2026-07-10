-- project.identifier_scopes: TEXT[] of bare scope strings -> JSONB list of {scope, datasetKey}
-- objects (see IdentifierScope / IdentifierScopeListTypeHandler). Existing rows carry over their
-- scopes with datasetKey left unset (null) -- an operator revisits Project settings to attach a
-- CLB dataset key per scope where matching is wanted.
--
-- Add-backfill-drop-rename rather than a single `ALTER COLUMN ... TYPE jsonb USING (...)`: Postgres
-- rejects a USING expression containing a subquery ("cannot use subquery in transform expression"),
-- and jsonb_agg(...) over unnest(identifier_scopes) needs one (the aggregate has to run over the
-- FROM unnest(...) row set). An UPDATE's SET expression has no such restriction.
ALTER TABLE project ADD COLUMN identifier_scopes_jsonb JSONB;

UPDATE project SET identifier_scopes_jsonb = (
  SELECT jsonb_agg(jsonb_build_object('scope', s)) FROM unnest(identifier_scopes) s
) WHERE identifier_scopes IS NOT NULL;

ALTER TABLE project DROP COLUMN identifier_scopes;
ALTER TABLE project RENAME COLUMN identifier_scopes_jsonb TO identifier_scopes;
