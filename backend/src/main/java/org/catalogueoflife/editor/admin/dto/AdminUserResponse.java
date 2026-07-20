package org.catalogueoflife.editor.admin.dto;

import org.catalogueoflife.editor.user.AppUser;

public record AdminUserResponse(Integer id, String username, String orcid, String displayName,
    String state, boolean admin) {
  public static AdminUserResponse of(AppUser u) {
    return new AdminUserResponse(u.getId(), u.getUsername(), u.getOrcid(), u.getDisplayName(),
        u.getState(), u.isAdmin());
  }
}
