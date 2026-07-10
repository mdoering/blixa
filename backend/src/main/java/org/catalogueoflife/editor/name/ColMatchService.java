package org.catalogueoflife.editor.name;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.name.dto.ColMatchCandidate;
import org.catalogueoflife.editor.name.dto.RankName;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

// Matches a single project usage against the published COL checklist (ClbMatchClient) and shapes
// the CLB response into ColMatchCandidate list: best match first (from the response's top-level
// `usage`, using its `type` as matchType), then each `alternatives[]` entry (matchType
// "ALTERNATIVE"). Consumed by GET /usages/{id}/col-match (ColMatchController); later reused by the
// match modal (Task 8) and the bulk-match workflow.
@Service
public class ColMatchService {

  private final ClbMatchClient clb;
  private final NameUsageMapper usages;
  private final ProjectService projects;

  public ColMatchService(ClbMatchClient clb, NameUsageMapper usages, ProjectService projects) {
    this.clb = clb;
    this.usages = usages;
    this.projects = projects;
  }

  public List<ColMatchCandidate> match(int userId, int projectId, int usageId) {
    Project project = projects.requireVisible(userId, projectId);
    NameUsage u = usages.findByIdInProject(projectId, usageId);
    if (u == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    // Project.nomCode is the NomCode enum (see ProjectService.parseNomCode); its name() is already
    // upper-case (ZOOLOGICAL, BOTANICAL, ...) which the CLB `code` param parses tolerantly.
    String code = project.getNomCode() == null ? null : project.getNomCode().name();
    String rank = u.getRank() == null ? null : u.getRank().toLowerCase(Locale.ROOT);
    List<RankName> classification = usages.findClassification(projectId, usageId);
    JsonNode root = clb.match(u.getScientificName(), u.getAuthorship(), rank, code, classification);

    List<ColMatchCandidate> out = new ArrayList<>();
    addCandidate(out, root.path("usage"), root.path("type").asString(null));
    for (JsonNode alt : root.path("alternatives")) {
      addCandidate(out, alt, "ALTERNATIVE");
    }
    return out;
  }

  // Skips missing/null usage nodes (e.g. the top-level `usage` when the CLB response's type is
  // NONE) so the caller ends up with an empty list rather than a bogus all-null candidate.
  private static void addCandidate(List<ColMatchCandidate> out, JsonNode node, String matchType) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    String colId = node.path("id").asString(null);
    if (colId == null) {
      return;
    }
    String name = node.path("name").asString(null);
    String authorship = node.path("authorship").asString(null);
    String rank = node.path("rank").asString(null);
    String status = node.path("status").asString(null);
    String classification = joinClassification(node.path("classification"));
    out.add(new ColMatchCandidate(colId, name, authorship, rank, status, matchType, classification));
  }

  // Root-first join of classification[].name (e.g. "Animalia > Chordata > ... > Panthera") -- lets
  // the match UI tell homonyms (same name/rank, different higher classification) apart.
  private static String joinClassification(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      return null;
    }
    List<String> names = new ArrayList<>();
    for (JsonNode n : node) {
      String name = n.path("name").asString(null);
      if (name != null) {
        names.add(name);
      }
    }
    return names.isEmpty() ? null : String.join(" > ", names);
  }

  // Bare COL id from a name_usage.alternative_id list (e.g. "col:6W3C4" -> "6W3C4"), or null if no
  // entry carries the (case-insensitive) "col:" prefix. Reused by a later bulk-match write path;
  // deliberately independent of NameUsageService.mergeColId (same idea, kept un-shared per the
  // task's isolation of this feature behind ClbMatchClient/ColMatchService).
  public static String colIdFrom(List<String> alternativeId) {
    if (alternativeId == null) {
      return null;
    }
    for (String s : alternativeId) {
      if (s != null && s.toLowerCase(Locale.ROOT).startsWith("col:")) {
        return s.substring("col:".length());
      }
    }
    return null;
  }
}
