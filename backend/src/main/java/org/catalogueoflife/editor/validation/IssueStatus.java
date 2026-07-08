package org.catalogueoflife.editor.validation;

// Stored as the enum's name() (TEXT column, see V7__issue.sql `status`). Reviewer lifecycle: a new
// finding starts OPEN; a reviewer accepts (-> ACCEPTED, work still to do) or rejects (-> REJECTED,
// ignore/suppress); an OPEN/REJECTED issue whose finding clears is deleted outright (nothing to
// keep); an ACCEPTED issue whose finding clears becomes DONE (completed work, kept as a record);
// a DONE issue whose finding recurs goes back to OPEN (regression). See
// ValidationService.revalidateUsage for the reconcile algorithm that drives these transitions.
public enum IssueStatus {
  OPEN, ACCEPTED, REJECTED, DONE
}
