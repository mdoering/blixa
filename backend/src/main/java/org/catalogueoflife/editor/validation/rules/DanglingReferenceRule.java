package org.catalogueoflife.editor.validation.rules;

import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.editor.validation.Finding;
import org.catalogueoflife.editor.validation.RuleContext;
import org.catalogueoflife.editor.validation.Severity;
import org.catalogueoflife.editor.validation.ValidationRule;
import org.springframework.stereotype.Component;

// A usage whose taxonomic reference_id[] cites a reference that no longer exists -- a dangling
// pointer. Unlike published_in_reference_id (FK ON DELETE SET NULL), the reference_id array has no
// DB-level FK (see NameUsageMapper), so a reference deleted outside the app's manual cleanup -- or
// carried in by a ColDP import -- leaves a stale id. That's a genuine data error, hence ERROR.
@Component
public class DanglingReferenceRule implements ValidationRule {

  @Override
  public String key() {
    return "dangling_reference";
  }

  @Override
  public Severity severity() {
    return Severity.ERROR;
  }

  @Override
  public Optional<Finding> evaluate(RuleContext ctx) {
    int dangling = ctx.danglingReferenceCount();
    if (dangling > 0) {
      return Optional.of(new Finding(key(), severity(),
          dangling + " taxonomic reference" + (dangling == 1 ? "" : "s")
              + " point to a reference that no longer exists",
          Map.of("count", dangling)));
    }
    return Optional.empty();
  }
}
