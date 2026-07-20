package org.catalogueoflife.editor.discussion.dto;

import java.util.Map;

// Resolved inline mentions found in a discussion/comment body, for the frontend to render as links.
//  * usages: usageId (as string) -> scientific name, for `#<id>` references that exist in the project.
//  * users:  mention token (an ORCID or a username, as written) -> the matched user, for `@<token>`.
// Unresolved tokens are simply omitted (rendered verbatim). `#Genus_species` name-string mentions
// are not resolved yet (fuzzy) -- deferred.
public record Mentions(Map<String, String> usages, Map<String, UserMention> users) {

  // A resolved @-mention: the display label to show, plus the user's ORCID (nullable) so the
  // frontend can link out to orcid.org when there is one.
  public record UserMention(String label, String orcid) {}
}
