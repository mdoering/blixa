package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.catalogueoflife.editor.audit.Change;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Links a discussion to changelog entries (the changes that resolved / relate to it). Any member may
// read the links; an editor may add or remove them.
@Service
public class DiscussionChangeService {

  private final DiscussionChangeMapper links;
  private final DiscussionMapper discussions;
  private final ProjectService projects;

  public DiscussionChangeService(DiscussionChangeMapper links, DiscussionMapper discussions,
      ProjectService projects) {
    this.links = links;
    this.discussions = discussions;
    this.projects = projects;
  }

  public List<Change> list(int userId, int projectId, int discussionId) {
    projects.requireRole(userId, projectId);
    requireDiscussion(projectId, discussionId);
    return links.findChanges(projectId, discussionId);
  }

  @Transactional
  public void link(int userId, int projectId, int discussionId, Integer changeId) {
    requireEditor(userId, projectId);
    requireDiscussion(projectId, discussionId);
    if (changeId == null || !links.changeInProject(projectId, changeId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "change not found");
    }
    links.link(projectId, discussionId, changeId);
  }

  @Transactional
  public void unlink(int userId, int projectId, int discussionId, int changeId) {
    requireEditor(userId, projectId);
    links.unlink(projectId, discussionId, changeId);
  }

  private void requireDiscussion(int projectId, int discussionId) {
    if (discussions.findByIdInProject(projectId, discussionId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "discussion not found");
    }
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "editor required");
    }
  }
}
