package org.catalogueoflife.editor.tree;

import java.util.List;
import org.catalogueoflife.editor.name.Pagination;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.tree.dto.PathNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TreeService {

  private final TreeMapper tree;
  private final ProjectService projects;

  public TreeService(TreeMapper tree, ProjectService projects) {
    this.tree = tree;
    this.projects = projects;
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
}
