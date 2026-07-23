-- Standard/defined taxon property keys per project. A "defined" key gives an otherwise free-form
-- property key (property.property) an optional human description and lets it appear in the project's
-- key overview even when nothing uses it yet. Used keys (from property.property) and defined keys
-- (this table) are unioned in the overview + autocomplete; reconciliation folds variant keys into a
-- canonical one. Mirrors the journal-name reconciliation on reference.container_title.
CREATE TABLE public.property_key (
    project_id integer NOT NULL,
    key text NOT NULL,
    description text,
    CONSTRAINT property_key_pkey PRIMARY KEY (project_id, key),
    CONSTRAINT property_key_project_id_fkey FOREIGN KEY (project_id)
        REFERENCES public.project(id) ON DELETE CASCADE
);
