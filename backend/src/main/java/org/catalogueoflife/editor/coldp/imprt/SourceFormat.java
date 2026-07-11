package org.catalogueoflife.editor.coldp.imprt;

import java.util.Locale;

// Which import input format an upload is, chosen by filename. New formats (e.g. Darwin Core Archive)
// add a constant + a detect() case + a SourceFormatAdapter, with no change to run()/loadTransactional.
public enum SourceFormat {
  COLDP,
  TXTREE;

  public static SourceFormat detect(String filename) {
    String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (f.endsWith(".txtree") || f.endsWith(".tree") || f.endsWith(".txt") || f.endsWith(".tsv")) {
      return TXTREE;
    }
    return COLDP; // .zip and anything else
  }
}
