-- Drop the unused REVIEWER project role. Existing reviewers become editors (trusted non-owners who
-- retain issue-triage rights); the role CHECK is tightened to owner/editor/viewer. The separate
-- issue "reviewer" (issue.reviewer_id -- who triaged an issue) is unrelated and unchanged.
UPDATE project_member SET role = 'editor' WHERE role = 'reviewer';
ALTER TABLE project_member DROP CONSTRAINT project_member_role_check;
ALTER TABLE project_member ADD CONSTRAINT project_member_role_check CHECK (role IN ('owner', 'editor', 'viewer'));
