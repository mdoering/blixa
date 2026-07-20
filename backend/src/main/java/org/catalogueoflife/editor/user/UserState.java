package org.catalogueoflife.editor.user;

// Account lifecycle: PENDING (registered, awaiting admin approval -- ORCID self-signups start here),
// ACTIVE (may use the app), DISABLED (blocked by an admin). Stored as the enum name in app_user.state.
public enum UserState {
  PENDING,
  ACTIVE,
  DISABLED
}
