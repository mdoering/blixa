package org.catalogueoflife.editor.validation;

// What a single ValidationRule produces for one RuleContext, at most one per entity per run.
// `context` is an arbitrary (nullable) small object -- typically a Map<String,Object> such as
// {"count": 2} or {"year": 1981, "referenceYear": 1978} -- that ValidationService serializes to
// JSON (the same #{param}::jsonb mechanism used by Change.diff/Project.metadata) and stores in the
// `issue.context` JSONB column.
public record Finding(String rule, Severity severity, String message, Object context) {}
