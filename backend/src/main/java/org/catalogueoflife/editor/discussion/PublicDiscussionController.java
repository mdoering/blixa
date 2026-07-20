package org.catalogueoflife.editor.discussion;

import java.util.List;
import org.catalogueoflife.editor.discussion.dto.CommentResponse;
import org.catalogueoflife.editor.discussion.dto.DiscussionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Unauthenticated read of PUBLIC discussions (SecurityConfig permits /api/public/** with no auth or
// CSRF). Only PUBLIC discussions and their comments are ever exposed -- INTERNAL ones 404, so no
// project-membership check is needed. Mentions are resolved the same way as the internal detail.
@RestController
@RequestMapping("/api/public/projects/{pid}/discussions")
public class PublicDiscussionController {

  private final DiscussionService discussions;
  private final DiscussionCommentMapper comments;
  private final DiscussionMentionService mentions;

  public PublicDiscussionController(DiscussionService discussions, DiscussionCommentMapper comments,
      DiscussionMentionService mentions) {
    this.discussions = discussions;
    this.comments = comments;
    this.mentions = mentions;
  }

  @GetMapping
  public List<DiscussionResponse> list(@PathVariable int pid) {
    return discussions.publicList(pid);
  }

  @GetMapping("/{id}")
  public DiscussionResponse get(@PathVariable int pid, @PathVariable int id) {
    return discussions.publicDetail(pid, id);
  }

  @GetMapping("/{id}/comments")
  public List<CommentResponse> comments(@PathVariable int pid, @PathVariable int id) {
    discussions.requirePublic(pid, id); // 404 unless this is a PUBLIC discussion
    return comments.findByDiscussion(pid, id).stream()
        .map(c -> CommentResponse.of(c, mentions.resolve(pid, c.getBody())))
        .toList();
  }
}
