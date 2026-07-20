package org.catalogueoflife.editor.discussion;

// Lifecycle of a discussion. UI-created discussions start OPEN; externally-submitted ones (Phase 4)
// start REVIEW until an editor accepts them. Stored as the enum name in discussion.status (TEXT).
public enum DiscussionStatus {
  REVIEW,
  OPEN,
  REJECTED,
  RESOLVED
}
