-- name_relation is now the single source of truth for basionym (and every other name relation);
-- the basionym_id column + its compound self-FK are removed. Import converts inbound ColDP
-- basionymID into a `basionym` relation; export emits it only via NameRelation.tsv. CASCADE drops
-- the inline (Postgres auto-named) compound FK (project_id, basionym_id) declared in V3.
ALTER TABLE name_usage DROP COLUMN basionym_id CASCADE;
