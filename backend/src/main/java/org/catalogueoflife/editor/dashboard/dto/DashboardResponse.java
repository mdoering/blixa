package org.catalogueoflife.editor.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

// The personal dashboard payload (GET /api/me/dashboard): an attention "inbox" + quick project
// access with headline metrics + recently-edited taxa, aggregated across the caller's projects.
public record DashboardResponse(
    Integer pendingUsers,                 // null unless the caller is a global admin
    PingSection pings,
    List<CountRef> reviewSubmissions,     // owner/editor projects with REVIEW discussions
    List<MissingMeta> missingMetadata,    // owned projects with incomplete metadata
    List<CountRef> openErrors,            // owner/editor projects with OPEN ERROR issues
    List<LockRef> myLocks,
    List<TaxonRef> recentTaxa,
    List<ProjectCard> projects) {

  public record ProjectCard(int id, String title, String alias, String role,
      long accepted, long synonyms, long openIssues) {}

  public record CountRef(int projectId, String projectTitle, long count) {}

  public record MissingMeta(int projectId, String projectTitle, List<String> missing) {}

  public record LockRef(int projectId, String projectTitle, int usageId, String scientificName,
      OffsetDateTime acquiredAt) {}

  public record TaxonRef(int projectId, String projectTitle, int usageId, String scientificName,
      OffsetDateTime editedAt) {}

  public record PingSection(long count, List<PingItem> items) {}

  public record PingItem(int projectId, String projectTitle, int discussionId, String title,
      String snippet, OffsetDateTime createdAt) {}
}
