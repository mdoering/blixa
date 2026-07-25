package org.catalogueoflife.editor.name.dto;

// Result of the project-wide "Link genera" batch (POST /projects/{pid}/link-genera): how many
// previously-unlinked binomials were newly linked, left ambiguous (several genera share the name and
// none is uniquely accepted), or unmatched (no genus usage of that name). Already-linked usages are
// not counted -- the batch never touches them.
public record LinkGeneraResponse(int linked, int ambiguous, int unmatched) {}
