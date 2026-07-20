package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.discussion.dto.DiscussionResponse;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Reverse view: the discussions that mention a given name_usage (#nameID in the body or a comment).
// Lives in the discussion package (not the name package) so all discussion wiring stays together.
@RestController
@RequestMapping("/api/projects/{pid}/usages/{uid}/discussions")
public class UsageDiscussionController {

  private final DiscussionUsageMapper links;
  private final ProjectService projects;
  private final CurrentUser currentUser;

  public UsageDiscussionController(DiscussionUsageMapper links, ProjectService projects,
      CurrentUser currentUser) {
    this.links = links;
    this.projects = projects;
    this.currentUser = currentUser;
  }

  @GetMapping
  public List<DiscussionResponse> list(@PathVariable int pid, @PathVariable int uid) {
    int actor = currentUser.require().getId();
    projects.requireRole(actor, pid);
    return links.findDiscussionsByUsage(pid, uid).stream().map(DiscussionResponse::of).toList();
  }
}
