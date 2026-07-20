package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.catalogueoflife.editor.discussion.dto.CommentResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiscussionCommentService {

  private static final String ENTITY = "discussion_comment";

  private final DiscussionCommentMapper comments;
  private final DiscussionMapper discussions;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final AppUserMapper users;
  private final DiscussionMentionService mentions;
  private final DiscussionLinkService links;

  public DiscussionCommentService(DiscussionCommentMapper comments, DiscussionMapper discussions,
      IdSeqMapper idSeq, ProjectService projects, AppUserMapper users,
      DiscussionMentionService mentions, DiscussionLinkService links) {
    this.comments = comments;
    this.discussions = discussions;
    this.idSeq = idSeq;
    this.projects = projects;
    this.users = users;
    this.mentions = mentions;
    this.links = links;
  }

  public List<CommentResponse> list(int userId, int projectId, int discussionId) {
    projects.requireRole(userId, projectId);
    requireDiscussion(projectId, discussionId);
    return comments.findByDiscussion(projectId, discussionId).stream()
        .map(c -> CommentResponse.of(c, mentions.resolve(projectId, c.getBody())))
        .toList();
  }

  @Transactional
  public CommentResponse create(int userId, int projectId, int discussionId, String body) {
    projects.requireRole(userId, projectId); // any member may comment
    requireDiscussion(projectId, discussionId);
    AppUser author = users.findById(userId);
    DiscussionComment c = new DiscussionComment();
    c.setProjectId(projectId);
    c.setId(idSeq.allocate(projectId, ENTITY));
    c.setDiscussionId(discussionId);
    c.setBody(body);
    c.setAuthorId(userId);
    c.setAuthorOrcid(author == null ? null : author.getOrcid());
    comments.insert(c);
    links.reconcile(projectId, discussionId);
    return response(projectId, c.getId());
  }

  @Transactional
  public CommentResponse update(int userId, int projectId, int discussionId, int id, String body,
      Integer version) {
    String role = projects.requireRole(userId, projectId);
    DiscussionComment existing = requireComment(projectId, discussionId, id);
    requireAuthorOrEditor(userId, role, existing);
    int updated = comments.update(projectId, id, body, version);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict: stale version");
    }
    links.reconcile(projectId, discussionId);
    return response(projectId, id);
  }

  @Transactional
  public void delete(int userId, int projectId, int discussionId, int id) {
    String role = projects.requireRole(userId, projectId);
    DiscussionComment existing = requireComment(projectId, discussionId, id);
    requireAuthorOrEditor(userId, role, existing);
    comments.delete(projectId, id);
    links.reconcile(projectId, discussionId);
  }

  private CommentResponse response(int projectId, int id) {
    DiscussionComment saved = comments.findByIdInProject(projectId, id);
    return CommentResponse.of(saved, mentions.resolve(projectId, saved.getBody()));
  }

  private void requireDiscussion(int projectId, int discussionId) {
    if (discussions.findByIdInProject(projectId, discussionId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "discussion not found");
    }
  }

  private DiscussionComment requireComment(int projectId, int discussionId, int id) {
    DiscussionComment c = comments.findByIdInProject(projectId, id);
    if (c == null || c.getDiscussionId() == null || c.getDiscussionId() != discussionId) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "comment not found");
    }
    return c;
  }

  private void requireAuthorOrEditor(int userId, String role, DiscussionComment c) {
    boolean isAuthor = c.getAuthorId() != null && c.getAuthorId() == userId;
    boolean isEditor = Role.OWNER.dbValue().equals(role) || Role.EDITOR.dbValue().equals(role);
    if (!isAuthor && !isEditor) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "author or editor required");
    }
  }
}
