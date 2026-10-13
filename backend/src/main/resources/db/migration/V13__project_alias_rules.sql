-- Project aliases become URL- and file-name-safe and unique (ProjectService.aliasProblem): 1-64
-- letters, digits, '.', '_' or '-', starting with a letter or digit, not all digits, and unique
-- case-insensitively across all projects.

-- Tidy existing aliases rather than drop them: runs of other characters become '-', leading
-- '.', '_' and '-' go, and the result is cut to 64 characters. Empty becomes NULL.
UPDATE public.project
SET alias = NULLIF(left(regexp_replace(regexp_replace(btrim(alias), '[^A-Za-z0-9._-]+', '-', 'g'),
                                       '^[._-]+', ''), 64), '')
WHERE alias IS NOT NULL;

-- A numeric alias would be read as a project id.
UPDATE public.project SET alias = NULL WHERE alias ~ '^[0-9]+$';

-- Duplicates: a public project keeps the alias over a private one, then the oldest project.
UPDATE public.project p
SET alias = NULL
WHERE p.alias IS NOT NULL
  AND EXISTS (SELECT 1 FROM public.project q
              WHERE lower(q.alias) = lower(p.alias)
                AND q.id <> p.id
                AND (q.is_public > p.is_public OR (q.is_public = p.is_public AND q.id < p.id)));

CREATE UNIQUE INDEX project_alias_lower_uq ON public.project USING btree (lower(alias));
