package org.catalogueoflife.editor.tree.dto;

// Body of PUT /api/projects/{pid}/tree/usages/{id}/parent (TreeService.move). parentId is
// nullable -- null makes the moved usage a root (parent_id = NULL). version is the optimistic
// lock on the MOVED usage (id), checked by TreeMapper.reparent's CAS UPDATE; a mismatch means
// someone else edited the node concurrently and comes back as 409, not a silent overwrite.
public record MoveRequest(Integer parentId, int version) {}
