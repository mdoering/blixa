package org.catalogueoflife.editor.project;

// One entry of project.identifier_scopes (JSONB list, see V17__identifier_scopes_jsonb.sql /
// IdentifierScopeListTypeHandler): a CURIE scope (e.g. "ipni", "gbif") the taxon Details form
// renders a real identifier field for, plus an OPTIONAL CLB dataset key. A scope is matchable
// (eligible for the "match all identifiers" flow) iff datasetKey is non-null/non-blank -- a scope
// with no datasetKey still gets its form field, it's just not backed by a CLB dataset to match
// against. `col` conventionally defaults its datasetKey to "3LXR" (see ProjectMetadataPage), but
// nothing here enforces that -- it's a frontend UX default, not a backend invariant.
public record IdentifierScope(String scope, String datasetKey) {}
