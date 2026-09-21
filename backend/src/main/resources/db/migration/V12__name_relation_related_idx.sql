-- Reverse lookup of name relations pointing AT a usage (GET .../relations/reverse).
CREATE INDEX name_relation_related_idx ON public.name_relation USING btree (project_id, related_usage_id);
