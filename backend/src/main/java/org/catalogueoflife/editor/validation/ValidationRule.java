package org.catalogueoflife.editor.validation;

import java.util.Optional;

// A single, independent validation check. Implementations are @Component beans (see the
// `rules` sub-package for the Task-1 starter set); ValidationService injects `List<ValidationRule>`
// (Spring collects every bean implementing this interface) and calls evaluate() once per usage per
// rule. A rule must be a pure function of its RuleContext -- no side effects, no DB access of its
// own -- so ValidationService.buildContext stays the single place that loads related data.
public interface ValidationRule {

  // Stable identifier stored in issue.rule and used to match a rule's finding across runs (the
  // UNIQUE (project_id, entity_type, entity_id, rule) constraint on `issue`, see V7__issue.sql).
  // Must never change once released, or existing issues orphan.
  String key();

  // The severity this rule's finding is always reported at (soft -- see Severity).
  Severity severity();

  // At most one Finding for the given context, or empty if nothing is wrong.
  Optional<Finding> evaluate(RuleContext ctx);
}
