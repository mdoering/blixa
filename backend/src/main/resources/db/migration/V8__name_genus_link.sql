-- A bi/trinomial's NOMENCLATURAL genus, pinned to a genus usage by id (see the genus-link design):
-- the exact genus the name's epithets agree with, distinct from the classification parent (which
-- diverges for synonyms). A project-internal resolution of the parsed genus token, not a ColDP
-- relation. Self-reference like parent_id; deleting a genus unlinks its names -- SET NULL nulls only
-- genus_id (keeping the NOT NULL project_id), Postgres 17's column-list ON DELETE SET NULL.
ALTER TABLE public.name_usage ADD COLUMN genus_id integer;

ALTER TABLE ONLY public.name_usage
    ADD CONSTRAINT name_usage_project_id_genus_id_fkey
    FOREIGN KEY (project_id, genus_id) REFERENCES public.name_usage(project_id, id) ON DELETE SET NULL (genus_id);
