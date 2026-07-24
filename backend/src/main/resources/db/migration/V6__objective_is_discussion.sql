-- Retire the `task` ("work-session") entity: the work objective a change/lock is made under is now
-- simply an OPEN discussion. `task_id` was null on every change and no tasks exist in practice, so
-- this is a mechanical repoint, not a data migration. See
-- docs/superpowers/specs/2026-07-24-work-objective-as-discussion-design.md.

-- change.task_id -> change.discussion_id (1:1 "authored under this objective").
ALTER TABLE public.change DROP CONSTRAINT change_task_id_fkey;
DROP INDEX public.change_task_idx;
ALTER TABLE public.change RENAME COLUMN task_id TO discussion_id;
CREATE INDEX change_discussion_idx ON public.change USING btree (project_id, discussion_id);
ALTER TABLE public.change ADD CONSTRAINT change_discussion_id_fkey
    FOREIGN KEY (project_id, discussion_id) REFERENCES public.discussion(project_id, id)
    ON DELETE SET NULL;

-- lock.task_id -> lock.discussion_id (the objective a soft lock was taken under).
ALTER TABLE public.lock DROP CONSTRAINT lock_task_id_fkey;
ALTER TABLE public.lock RENAME COLUMN task_id TO discussion_id;
ALTER TABLE public.lock ADD CONSTRAINT lock_discussion_id_fkey
    FOREIGN KEY (project_id, discussion_id) REFERENCES public.discussion(project_id, id)
    ON DELETE SET NULL;

-- The task entity is gone (its sequence + index drop with the table).
DROP TABLE public.task;
