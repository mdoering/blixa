package org.catalogueoflife.editor.project;

import java.util.Locale;

public enum Role {
  OWNER, EDITOR, VIEWER;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Role fromDb(String v) {
    return Role.valueOf(v.toUpperCase(Locale.ROOT));
  }
}
