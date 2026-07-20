package org.catalogueoflife.editor.discussion;

// Who can see a discussion. INTERNAL = project members only; PUBLIC = readable by anyone via the
// public route (Phase 3). Phase 1 keeps everything INTERNAL. Stored as the enum name in
// discussion.visibility (TEXT).
public enum DiscussionVisibility {
  INTERNAL,
  PUBLIC
}
