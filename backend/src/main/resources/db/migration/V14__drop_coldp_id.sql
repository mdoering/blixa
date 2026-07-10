-- coldp_id is redundant: identity is the per-project numeric id; external ids live in alternative_id.
-- DROP COLUMN also removes the UNIQUE (project_id, coldp_id) constraints defined in V3.
ALTER TABLE name_usage DROP COLUMN coldp_id;
ALTER TABLE reference  DROP COLUMN coldp_id;
ALTER TABLE author     DROP COLUMN coldp_id;
