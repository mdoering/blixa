package org.catalogueoflife.editor.validation.rules;

import org.gbif.nameparser.api.NameType;

// Shared gate for the name-structure rules: a name the parser couldn't treat as a normal scientific
// name (formula/informal/placeholder/identifier/other) shouldn't be flagged for epithet casing,
// missing genus, etc. A null type (e.g. a hand-built test context) is treated as scientific.
final class NameTypes {

  private NameTypes() {}

  static boolean isNonScientific(NameType type) {
    return type != null && type != NameType.SCIENTIFIC;
  }
}
