package org.catalogueoflife.editor.coldp.imprt;

import java.util.Locale;

// Which import input format an upload is. TXTREE is chosen by filename; COLDP and DWCA are BOTH .zip
// and so can't be told apart by name -- detect() returns COLDP for a .zip and ImportRunService.start
// content-sniffs the zip for a meta.xml descriptor to upgrade it to DWCA (see start). A new format
// adds a constant + a SourceFormatAdapter, with no change to run()/loadTransactional.
public enum SourceFormat {
  COLDP,
  TXTREE,
  DWCA;

  public static SourceFormat detect(String filename) {
    String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (f.endsWith(".txtree") || f.endsWith(".tree") || f.endsWith(".txt") || f.endsWith(".tsv")) {
      return TXTREE;
    }
    return COLDP; // .zip and anything else (DWCA is separated from COLDP by content sniff in start)
  }
}
