package org.catalogueoflife.editor.dashboard;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.dashboard.DashboardMapper.ProjectCount;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.CountRef;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.LockRef;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.MissingMeta;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.PingItem;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.PingSection;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.ProjectCard;
import org.catalogueoflife.editor.dashboard.dto.DashboardResponse.TaxonRef;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectMemberMapper;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.user.AppUser;
import org.springframework.stereotype.Service;

// Builds the personal dashboard by aggregating across the caller's projects. All project-scoped
// queries are grouped (one round-trip each over the membership id list), then joined in memory.
@Service
public class DashboardService {

  private static final int RECENT_LIMIT = 8;
  private static final int PING_PREVIEW = 5;

  private final DashboardMapper dashboard;
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;

  public DashboardService(DashboardMapper dashboard, ProjectMapper projects,
      ProjectMemberMapper members) {
    this.dashboard = dashboard;
    this.projects = projects;
    this.members = members;
  }

  public DashboardResponse build(AppUser me) {
    int uid = me.getId();
    Integer pendingUsers = me.isAdmin() ? dashboard.countPendingUsers() : null;

    List<Project> mine = projects.findByMember(uid);
    Map<Integer, String> titleById = new HashMap<>();
    for (Project p : mine) titleById.put(p.getId(), p.getTitle());

    List<LockRef> myLocks = dashboard.myLocks(uid).stream()
        .map(l -> new LockRef(l.projectId(), titleById.get(l.projectId()), l.usageId(),
            l.scientificName(), l.acquiredAt()))
        .toList();

    List<Integer> pids = mine.stream().map(Project::getId).toList();
    if (pids.isEmpty()) {
      return new DashboardResponse(pendingUsers, new PingSection(0, List.of()),
          List.of(), List.of(), List.of(), myLocks, List.of(), List.of());
    }

    Map<Integer, Map<String, Long>> statusByProject = grouped(dashboard.usageStatusCounts(pids));
    Map<Integer, Long> openIssues = single(dashboard.openIssueCounts(pids));
    Map<Integer, Long> openErrors = single(dashboard.openErrorCounts(pids));
    Map<Integer, Long> reviews = single(dashboard.reviewCounts(pids));

    List<ProjectCard> cards = new ArrayList<>();
    List<CountRef> reviewSubmissions = new ArrayList<>();
    List<CountRef> openErrorRefs = new ArrayList<>();
    List<MissingMeta> missingMetadata = new ArrayList<>();
    for (Project p : mine) {
      int id = p.getId();
      String role = members.findRole(id, uid);
      Map<String, Long> sc = statusByProject.getOrDefault(id, Map.of());
      long accepted = sc.getOrDefault("ACCEPTED", 0L);
      long synonyms = sc.getOrDefault("SYNONYM", 0L) + sc.getOrDefault("MISAPPLIED", 0L);
      cards.add(new ProjectCard(id, p.getTitle(), p.getAlias(), role, accepted, synonyms,
          openIssues.getOrDefault(id, 0L)));

      boolean ownerOrEditor = Role.OWNER.dbValue().equals(role) || Role.EDITOR.dbValue().equals(role);
      if (ownerOrEditor) {
        long rev = reviews.getOrDefault(id, 0L);
        if (rev > 0) reviewSubmissions.add(new CountRef(id, p.getTitle(), rev));
        long err = openErrors.getOrDefault(id, 0L);
        if (err > 0) openErrorRefs.add(new CountRef(id, p.getTitle(), err));
      }
      if (Role.OWNER.dbValue().equals(role)) {
        List<String> miss = missingFields(p);
        if (!miss.isEmpty()) missingMetadata.add(new MissingMeta(id, p.getTitle(), miss));
      }
    }

    List<TaxonRef> recentTaxa = dashboard.recentTaxa(uid, pids, RECENT_LIMIT).stream()
        .map(r -> new TaxonRef(r.projectId(), titleById.get(r.projectId()), r.usageId(),
            r.scientificName(), r.editedAt()))
        .toList();

    OffsetDateTime since = me.getDashboardSeenAt();
    long pingCount = dashboard.pingCount(uid, me.getOrcid(), me.getUsername(), pids, since);
    List<PingItem> pingItems = dashboard.pings(uid, me.getOrcid(), me.getUsername(), pids, since,
        PING_PREVIEW).stream()
        .map(r -> new PingItem(r.projectId(), titleById.get(r.projectId()), r.discussionId(),
            r.title(), r.snippet(), r.createdAt()))
        .toList();

    return new DashboardResponse(pendingUsers, new PingSection(pingCount, pingItems),
        reviewSubmissions, missingMetadata, openErrorRefs, myLocks, recentTaxa, cards);
  }

  private static List<String> missingFields(Project p) {
    List<String> miss = new ArrayList<>();
    if (p.getLicense() == null) miss.add("license");
    if (isBlank(p.getDescription())) miss.add("description");
    if (isBlank(p.getGeographicScope())) miss.add("geographic scope");
    if (isBlank(p.getTaxonomicScope())) miss.add("taxonomic scope");
    return miss;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  // projectId -> (key -> count), for multi-row-per-project groupings (status counts).
  private static Map<Integer, Map<String, Long>> grouped(List<ProjectCount> rows) {
    Map<Integer, Map<String, Long>> out = new HashMap<>();
    for (ProjectCount r : rows) {
      out.computeIfAbsent(r.projectId(), k -> new HashMap<>()).put(r.key(), r.count());
    }
    return out;
  }

  // projectId -> count, for one-row-per-project groupings.
  private static Map<Integer, Long> single(List<ProjectCount> rows) {
    Map<Integer, Long> out = new HashMap<>();
    for (ProjectCount r : rows) out.put(r.projectId(), r.count());
    return out;
  }
}
