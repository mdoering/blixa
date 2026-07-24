package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.gbif.nameparser.util.UnicodeUtils;
import org.springframework.stereotype.Component;

// Suspicious Unicode in the scientific name (CLB HOMOGLYPH_CHARACTERS / DIACRITIC_CHARACTERS):
//  - homoglyphs -- look-alike characters from another script (e.g. a Cyrillic 'а' for Latin 'a')
//    that are invisible to the eye but break search/matching. Always flagged.
//  - diacritics -- scientific names are Latinized/ASCII, so a diacritic is an error; flagged only for
//    scientific-typed names (an informal name may legitimately carry them).
@Component
public class SuspiciousNameCharactersRule implements ValidationRule {

  @Override
  public String key() {
    return "suspicious_name_characters";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    NameUsage u = ctx.usage();
    String name = u.getScientificName();
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    if (UnicodeUtils.containsHomoglyphs(name)) {
      return Optional.of(new Finding(key(), severity(),
          "the scientific name contains homoglyph (look-alike, wrong-script) characters", null));
    }
    if (!NameTypes.isNonScientific(u.getNameType()) && UnicodeUtils.containsDiacritics(name)) {
      return Optional.of(new Finding(key(), severity(),
          "the scientific name contains diacritics (scientific names should be ASCII-Latinized)", null));
    }
    return Optional.empty();
  }
}
