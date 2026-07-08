package org.catalogueoflife.editor.validation;

// A rule's/finding's severity (see V7__issue.sql's `severity` TEXT column, stored as name()).
// Global Constraint: severity is soft -- NOTHING a rule finds ever blocks a save, it only informs
// the reviewer lifecycle (see IssueStatus).
public enum Severity {
  INFO, WARNING, ERROR
}
