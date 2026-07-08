package org.catalogueoflife.editor.tree;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.audit.Operation;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.tree.dto.MoveRequest;
import org.catalogueoflife.editor.tree.dto.PathNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TreeService {

  private final TreeMapper tree;
  private final NameUsageMapper usages;
  private final ProjectService projects;
  private final AuditService audit;
  private final ObjectMapper objectMapper;

  public TreeService(TreeMapper tree, NameUsageMapper usages, ProjectService projects, AuditService audit,
      ObjectMapper objectMapper) {
    this.tree = tree;
    this.usages = usages;
    this.projects = projects;
    this.audit = audit;
    this.objectMapper = objectMapper;
  }

  public List<TreeNode> listRoots(int actorId, int projectId, int limit, int offset) {
    projects.requireRole(actorId, projectId); // any member may read
    return tree.findRoots(projectId, Pagination.clampLimit(limit), Pagination.clampOffset(offset));
  }

  public List<TreeNode> listChildren(int actorId, int projectId, int parentId, int limit, int offset) {
    projects.requireRole(actorId, projectId);
    return tree.findChildren(projectId, parentId, Pagination.clampLimit(limit),
        Pagination.clampOffset(offset));
  }

  public List<PathNode> path(int actorId, int projectId, int id) {
    projects.requireRole(actorId, projectId);
    List<PathNode> path = tree.findPath(projectId, id);
    if (path.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    return path;
  }

  // Cycle-safe reparent: requires owner/editor; the moved usage and (if given) the new parent
  // must both be ACCEPTED usages in this project; the new parent may not be the node itself or
  // one of its own descendants (that would detach the moved subtree from the tree root by making
  // it its own ancestor); parentId == null moves the node to the top level (a new root). The
  // actual UPDATE is optimistic-locked (TreeMapper.reparent) -- 0 rows means a concurrent edit
  // raced us, surfaced as 409 rather than silently doing nothing.
  @Transactional
  public void move(int actorId, int projectId, int id, MoveRequest req) {
    requireEditor(actorId, projectId);
    // Serializes reparents within this project for the rest of the transaction (see
    // TreeMapper.lockProject) so the isDescendant check below and the reparent write are atomic
    // with respect to any other concurrent move/create/update touching this project's tree.
    tree.lockProject(projectId);
    NameUsage moved = usages.findByIdInProject(projectId, id);
    if (moved == null || moved.getStatus() != Status.ACCEPTED) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    Integer parentId = req.parentId();
    if (parentId != null) {
      if (parentId == id) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a usage cannot be its own parent");
      }
      NameUsage parent = usages.findByIdInProject(projectId, parentId);
      if (parent == null || parent.getStatus() != Status.ACCEPTED) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parent not found or not accepted");
      }
      if (tree.isDescendant(projectId, id, parentId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "would create a cycle");
      }
    }
    // Snapshot BEFORE the reparent UPDATE below: reparent() mutates the row directly in SQL
    // (never touching `moved` in memory), so `moved` itself would still look like a valid
    // pre-move snapshot -- but relying on that is fragile since it only stays correct as long as
    // no code path re-fetches/mutates `moved` in place first. Converting to a Map here makes the
    // audit snapshot explicit and independent, mirroring ReferenceService.update/NameUsageService.update.
    @SuppressWarnings("unchecked")
    Map<String, Object> before = objectMapper.convertValue(moved, Map.class);
    int updated = tree.reparent(projectId, id, parentId, req.version());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    NameUsage after = usages.findByIdInProject(projectId, id);
    audit.record(projectId, actorId, "name_usage", id, Operation.UPDATE, before, after);
  }

  private void requireEditor(int actorId, int projectId) {
    String role = projects.requireRole(actorId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
