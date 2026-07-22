package org.catalogueoflife.editor.tree;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.gbif.txtree.SimpleTreeNode;
import org.catalogueoflife.editor.tree.dto.MoveRequest;
import org.catalogueoflife.editor.tree.dto.PathNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{pid}/tree")
public class TreeController {

  private final TreeService service;
  private final CurrentUser currentUser;

  public TreeController(TreeService service, CurrentUser currentUser) {
    this.service = service;
    this.currentUser = currentUser;
  }

  @GetMapping("/roots")
  public List<TreeNode> roots(@PathVariable int pid,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "false") boolean unassessed) {
    int uid = currentUser.require().getId();
    return service.listRoots(uid, pid, limit, offset, unassessed);
  }

  @GetMapping("/children/{parentId}")
  public List<TreeNode> children(@PathVariable int pid, @PathVariable int parentId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "false") boolean unassessed) {
    int uid = currentUser.require().getId();
    return service.listChildren(uid, pid, parentId, limit, offset, unassessed);
  }

  @GetMapping("/path/{id}")
  public List<PathNode> path(@PathVariable int pid, @PathVariable int id) {
    int uid = currentUser.require().getId();
    return service.path(uid, pid, id);
  }

  // Streams the accepted subtree rooted at {id} as a TextTree (.txtree) attachment -- the
  // "Download subtree" action in the tree view. Any project member (read).
  @GetMapping("/{id}/subtree.txtree")
  public void subtreeTxtree(@PathVariable int pid, @PathVariable int id, HttpServletResponse response)
      throws IOException {
    int uid = currentUser.require().getId();
    SimpleTreeNode root = service.buildSubtree(uid, pid, id); // requireRole + 404 before headers
    response.setContentType("text/plain;charset=UTF-8");
    response.setHeader("Content-Disposition",
        "attachment; filename=\"project-" + pid + "-subtree-" + id + ".txtree\"");
    SubtreeTxtree.write(response.getOutputStream(), root);
  }

  // 200 (not 204): the plan's move test cases assert on the plain success status here, so we
  // leave this as Spring's default 200-with-empty-body for a void handler rather than opting
  // into 204 like the synonym-link toggles in NameUsageController.
  @PutMapping("/usages/{id}/parent")
  public void move(@PathVariable int pid, @PathVariable int id, @RequestBody MoveRequest req) {
    int uid = currentUser.require().getId();
    service.move(uid, pid, id, req);
  }
}
