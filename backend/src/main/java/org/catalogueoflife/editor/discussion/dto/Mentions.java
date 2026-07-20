package org.catalogueoflife.editor.discussion.dto;

import java.util.Map;

// Resolved inline mentions found in a discussion/comment body, for the frontend to render as links.
//  * usages: usageId (as string) -> scientific name, for `#<id>` references that exist in the project.
//  * orcids: ORCID -> display name, for `@<orcid>` references that match a known user.
// Unresolved tokens are simply omitted (rendered verbatim). `#Genus_species` name-string mentions
// are not resolved yet (fuzzy) -- deferred.
public record Mentions(Map<String, String> usages, Map<String, String> orcids) {}
