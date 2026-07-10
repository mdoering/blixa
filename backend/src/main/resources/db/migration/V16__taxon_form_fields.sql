-- name_usage.link is an unused general free-text link with no dedicated UI/API purpose; drop it.
-- (published_in_page_link is a distinct, still-used column -- untouched.)
-- project.identifier_scopes and reference.accessed are new columns for upcoming taxon-form work
-- (SELECT-only wiring lands here; write-side + UI land in later tasks).
ALTER TABLE name_usage DROP COLUMN link;
ALTER TABLE project    ADD COLUMN identifier_scopes TEXT[];
ALTER TABLE reference  ADD COLUMN accessed TEXT;
