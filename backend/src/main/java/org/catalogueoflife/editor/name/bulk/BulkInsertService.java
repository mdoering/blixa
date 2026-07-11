package org.catalogueoflife.editor.name.bulk;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.editor.merge.NameMatcher;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertRequest;
import org.catalogueoflife.editor.name.bulk.dto.BulkInsertResult;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse;
import org.catalogueoflife.editor.name.bulk.dto.BulkPreviewResponse.PreviewNode;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.dto.NameUsageResponse;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.TreeMapper;
import org.gbif.nameparser.api.NomCode;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BulkInsertService {

  static final int MAX_NAMES = 1000;

  private final NameUsageService usageService;
  private final NameUsageMapper usages;
  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final NameParserService parser;
  private final TreeMapper tree;

  public BulkInsertService(NameUsageService usageService, NameUsageMapper usages,
      ProjectService projects, ProjectMapper projectMapper, NameParserService parser,
      TreeMapper tree) {
    this.usageService = usageService;
    this.usages = usages;
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.parser = parser;
    this.tree = tree;
  }

  public BulkPreviewResponse preview(int userId, int projectId, BulkInsertRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    resolveTarget(projectId, req.targetId());       // 400/404 if target invalid
    BulkMode mode = parseMode(req.mode());

    List<SimpleTreeNode> roots;
    try {
      roots = Tree.simple(new StringReader(req.text())).getRoot();
    } catch (IllegalArgumentException | IOException e) {
      return BulkPreviewResponse.invalid(e.getMessage());
    }
    if (roots.isEmpty()) {
      return BulkPreviewResponse.invalid("No names found in the input");
    }
    if (mode == BulkMode.SYNONYMS && !isFlat(roots)) {
      return BulkPreviewResponse.invalid(
          "Synonymy mode requires a flat list of names (no indentation)");
    }
    int total = countNodes(roots, mode);
    if (total > MAX_NAMES) {
      return BulkPreviewResponse.invalid("This list is too large for a direct insert (" + total
          + " > " + MAX_NAMES + "). Import it as a new dataset instead.");
    }

    Set<String> existingKeys = existingCanonicalKeys(projectId, req.targetId(), mode);
    NomCode nomCode = project.getNomCode();
    int[] counts = new int[3]; // accepted, synonyms, duplicates
    List<PreviewNode> nodes = mode == BulkMode.SYNONYMS
        ? previewFlatSynonyms(roots, nomCode, existingKeys, counts)
        : previewChildren(roots, nomCode, existingKeys, counts, true);
    return new BulkPreviewResponse(true, null, counts[0] + counts[1],
        counts[0], counts[1], counts[2], nodes);
  }

  // All-or-nothing bulk insert. Reuses NameUsageService.create (parse, id-seq, insert, taxon-info,
  // audit, validation event) and linkSynonym, so this stays DRY with single-add. The whole run is
  // one transaction: create() is @Transactional REQUIRED, so each call joins THIS transaction and
  // any failure rolls the entire batch back. The project tree is advisory-locked once up front
  // (create() re-locks reentrantly for each parented child).
  @Transactional
  public BulkInsertResult insert(int userId, int projectId, BulkInsertRequest req) {
    requireEditor(userId, projectId);
    Project project = requireProject(projectId);
    BulkMode mode = parseMode(req.mode());

    List<SimpleTreeNode> roots;
    try {
      roots = Tree.simple(new StringReader(req.text())).getRoot();
    } catch (IllegalArgumentException | IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (roots.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No names found in the input");
    }
    if (mode == BulkMode.SYNONYMS && !isFlat(roots)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Synonymy mode requires a flat list of names (no indentation)");
    }
    int total = countNodes(roots, mode);
    if (total > MAX_NAMES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "This list is too large for a direct insert (" + total + " > " + MAX_NAMES
              + "). Import it as a new dataset instead.");
    }

    // Lock the project tree BEFORE resolving/validating the target, so the target's ACCEPTED
    // status is read under the lock (matches NameUsageService.demote/promote convention). This
    // closes a TOCTOU window where a concurrent demote of the target between the status read and
    // the lock could otherwise let a synonymy-mode insert link synonyms to a no-longer-accepted
    // target (synonymy mode has no re-check under the lock).
    tree.lockProject(projectId);
    NameUsage target = resolveTarget(projectId, req.targetId());

    NomCode nomCode = project.getNomCode();
    int[] counts = new int[2]; // created, linked
    if (mode == BulkMode.SYNONYMS) {
      for (SimpleTreeNode n : roots) {
        int synId = createUsage(userId, projectId, n, "SYNONYM", null, nomCode);
        usageService.linkSynonym(userId, projectId, synId, target.getId());
        counts[0]++;
        counts[1]++;
      }
    } else {
      insertChildren(userId, projectId, roots, target.getId(), nomCode, counts);
    }
    return new BulkInsertResult(counts[0], counts[1], target.getId());
  }

  private void insertChildren(int userId, int projectId, List<SimpleTreeNode> nodes,
      Integer parentId, NomCode nomCode, int[] counts) {
    for (SimpleTreeNode n : nodes) {
      int id = createUsage(userId, projectId, n, "ACCEPTED", parentId, nomCode);
      counts[0]++;
      for (SimpleTreeNode s : n.synonyms) {
        int synId = createUsage(userId, projectId, s, "SYNONYM", null, nomCode);
        usageService.linkSynonym(userId, projectId, synId, id);
        counts[0]++;
        counts[1]++;
      }
      insertChildren(userId, projectId, n.children, id, nomCode, counts);
    }
  }

  // Builds a CreateNameUsageRequest and delegates to NameUsageService.create. Rank is pre-resolved
  // to a non-blank value (create requires @NotBlank rank): the [rank] suffix if present, else the
  // parser-inferred rank, else "unranked".
  private int createUsage(int userId, int projectId, SimpleTreeNode node, String status,
      Integer parentId, NomCode nomCode) {
    String rank = effectiveRank(node, nomCode);
    CreateNameUsageRequest r = new CreateNameUsageRequest(
        node.name, null, rank, status, parentId,
        null, null, null, null, null, null, null,
        node.extinct ? Boolean.TRUE : null,
        null, null, null, null);
    NameUsageResponse created = usageService.create(userId, projectId, r);
    return created.id();
  }

  // --- shared helpers (reused by insert() in Task 3) ---

  BulkMode parseMode(String raw) {
    try {
      return BulkMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid mode: " + raw);
    }
  }

  void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }

  Project requireProject(int projectId) {
    Project p = projectMapper.findById(projectId);
    if (p == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
    }
    return p;
  }

  // The target must be an accepted usage in the project (children attach to it; synonyms point at it).
  NameUsage resolveTarget(int projectId, int targetId) {
    NameUsage t = usages.findByIdInProject(projectId, targetId);
    if (t == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target not in project");
    }
    if (t.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target must be an accepted usage");
    }
    return t;
  }

  static boolean isFlat(List<SimpleTreeNode> roots) {
    return roots.stream().allMatch(n -> n.children.isEmpty() && n.synonyms.isEmpty());
  }

  static int countNodes(List<SimpleTreeNode> roots, BulkMode mode) {
    if (mode == BulkMode.SYNONYMS) {
      return roots.size();
    }
    int n = 0;
    for (SimpleTreeNode r : roots) {
      n += 1 + r.synonyms.size() + countNodes(r.children, BulkMode.CHILDREN);
    }
    return n;
  }

  // Canonical (author-stripped) keys of the usages already sitting where this batch would land --
  // CHILDREN mode against the target's existing accepted children, SYNONYMS mode against its
  // existing synonyms. Keying both the DB rows here and the incoming nodes (see previewChildren/
  // previewFlatSynonyms) through NameMatcher.canonicalKey means an input line carrying authorship
  // (e.g. GBIF text-tree's "Panthera leo (Linnaeus, 1758)") still matches an authorship-free DB row.
  private Set<String> existingCanonicalKeys(int projectId, int targetId, BulkMode mode) {
    List<NameUsage> existing = mode == BulkMode.SYNONYMS
        ? usages.findSynonymsOfAccepted(projectId, targetId)
        : usages.findChildrenByParent(projectId, targetId);
    return existing.stream().map(NameMatcher::canonicalKey).collect(Collectors.toSet());
  }

  // Runs the same parse the insert will, so the previewed rank/status match what gets stored. The
  // usage is discarded (never inserted).
  NameUsage toUsage(int projectId, int userId, SimpleTreeNode node, Status status,
      Integer parentId, NomCode nomCode) {
    NameUsage u = new NameUsage();
    u.setProjectId(projectId);
    u.setScientificName(node.name);
    u.setRank(node.rank);
    u.setStatus(status);
    u.setParentId(parentId);
    u.setExtinct(node.extinct ? Boolean.TRUE : null);
    u.setModifiedBy(userId);
    parser.parseInto(u, nomCode);
    if (u.getRank() == null || u.getRank().isBlank()) {
      u.setRank("unranked");
    }
    return u;
  }

  private String effectiveRank(SimpleTreeNode node, NomCode nomCode) {
    return toUsage(0, 0, node, Status.ACCEPTED, null, nomCode).getRank();
  }

  private List<PreviewNode> previewChildren(List<SimpleTreeNode> nodes, NomCode nomCode,
      Set<String> existingKeys, int[] counts, boolean topLevel) {
    List<PreviewNode> out = new ArrayList<>();
    for (SimpleTreeNode n : nodes) {
      counts[0]++;
      // Same parsed usage backs both the previewed rank and the dup check -- parsed once per node.
      NameUsage u = toUsage(0, 0, n, Status.ACCEPTED, null, nomCode);
      boolean dup = topLevel && existingKeys.contains(NameMatcher.canonicalKey(u));
      if (dup) counts[2]++;
      List<PreviewNode> syns = new ArrayList<>();
      for (SimpleTreeNode s : n.synonyms) {
        counts[1]++;
        syns.add(new PreviewNode(s.name, effectiveRank(s, nomCode), "SYNONYM", false, false,
            List.of(), List.of()));
      }
      List<PreviewNode> kids = previewChildren(n.children, nomCode, existingKeys, counts, false);
      out.add(new PreviewNode(n.name, u.getRank(), "ACCEPTED", n.extinct, dup,
          kids, syns));
    }
    return out;
  }

  private List<PreviewNode> previewFlatSynonyms(List<SimpleTreeNode> roots, NomCode nomCode,
      Set<String> existingKeys, int[] counts) {
    List<PreviewNode> out = new ArrayList<>();
    for (SimpleTreeNode n : roots) {
      counts[1]++;
      // Same parsed usage backs both the previewed rank and the dup check -- parsed once per node.
      NameUsage u = toUsage(0, 0, n, Status.ACCEPTED, null, nomCode);
      boolean dup = existingKeys.contains(NameMatcher.canonicalKey(u));
      if (dup) counts[2]++;
      out.add(new PreviewNode(n.name, u.getRank(), "SYNONYM", false, dup,
          List.of(), List.of()));
    }
    return out;
  }
}
