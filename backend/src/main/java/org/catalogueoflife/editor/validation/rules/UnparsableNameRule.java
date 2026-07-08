package org.catalogueoflife.editor.validation.rules;

import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.gbif.nameparser.api.ParsedName;
import org.springframework.stereotype.Component;

// Flags a usage whose scientific name the GBIF name-parser could not fully parse.
// NameParserService (see parse/NameParserService.java) stores the raw ParsedName.State name()
// ("COMPLETE"/"PARTIAL"/"NONE") on a successful parse, or the sentinel "UNPARSABLE" (not itself a
// State enum value) when parsing throws entirely -- any parseState other than COMPLETE means the
// atomized fields (genus/specificEpithet/...) may be missing or unreliable.
@Component
public class UnparsableNameRule implements ValidationRule {

  @Override
  public String key() {
    return "unparsable_name";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    String state = ctx.usage().getParseState();
    if (state != null && !ParsedName.State.COMPLETE.name().equals(state)) {
      return Optional.of(new Finding(key(), severity(),
          "scientific name not fully parsed (state=" + state + ")", null));
    }
    return Optional.empty();
  }
}
