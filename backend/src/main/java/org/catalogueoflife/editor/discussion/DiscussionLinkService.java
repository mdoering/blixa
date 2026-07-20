package org.catalogueoflife.editor.discussion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Keeps discussion_usage in sync with the #nameID references in a discussion's body + all its
// comments. Called after any discussion or comment write. Kept separate from the two services so
// neither depends on the other (avoids a service cycle) -- this depends only on mappers + the
// mention parser.
@Service
public class DiscussionLinkService {

  private final DiscussionMapper discussions;
  private final DiscussionCommentMapper comments;
  private final DiscussionUsageMapper links;
  private final DiscussionMentionService mentions;

  public DiscussionLinkService(DiscussionMapper discussions, DiscussionCommentMapper comments,
      DiscussionUsageMapper links, DiscussionMentionService mentions) {
    this.discussions = discussions;
    this.comments = comments;
    this.links = links;
    this.mentions = mentions;
  }

  @Transactional
  public void reconcile(int projectId, int discussionId) {
    Discussion d = discussions.findByIdInProject(projectId, discussionId);
    if (d == null) return; // deleted concurrently; its links were CASCADE-dropped
    List<String> texts = new ArrayList<>();
    texts.add(d.getBody());
    texts.addAll(comments.findBodiesByDiscussion(projectId, discussionId));
    Set<Integer> usageIds = mentions.existingUsageRefs(projectId, texts);
    links.clear(projectId, discussionId);
    for (int usageId : usageIds) {
      links.link(projectId, discussionId, usageId);
    }
  }
}
