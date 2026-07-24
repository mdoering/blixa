package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// Flags a taxon that carries duplicate supplementary records -- two distributions for the same area,
// two vernaculars with the same name+language, two media for the same url, two estimates of the same
// value+type, or two properties with the same property+value (CLB DUPLICATE_DISTRIBUTIONS /
// _VERNACULAR_NAMES / _MEDIA / _ESTIMATES / _TAXON_PROPERTIES, folded into one rule). The duplicate
// detection is a per-usage group-by in NameUsageMapper.duplicateChildTypes; this rule just reports
// which child types came back.
@Component
public class DuplicateChildRecordsRule implements ValidationRule {

  @Override
  public String key() {
    return "duplicate_child_records";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    Set<String> types = ctx.duplicateChildTypes();
    if (types == null || types.isEmpty()) {
      return Optional.empty();
    }
    String joined = types.stream().sorted().collect(Collectors.joining(", "));
    return Optional.of(new Finding(key(), severity(),
        "this taxon has duplicate " + joined + " records", null));
  }
}
