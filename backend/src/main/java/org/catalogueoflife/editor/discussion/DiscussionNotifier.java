package org.catalogueoflife.editor.discussion;

import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Emails the followers of a discussion when something changes. Best-effort: never throws, so a
// notification problem can't break the comment/status write that triggered it. (Sending is
// synchronous for now; move behind @Async if follower lists get large.)
@Service
public class DiscussionNotifier {

  private final DiscussionFollowMapper follows;
  private final AppUserMapper users;
  private final EmailService email;
  private final String baseUrl;

  public DiscussionNotifier(DiscussionFollowMapper follows, AppUserMapper users, EmailService email,
      @Value("${coldp.mail.base-url:}") String baseUrl) {
    this.follows = follows;
    this.users = users;
    this.email = email;
    this.baseUrl = baseUrl;
  }

  public void notifyNewComment(int projectId, int discussionId, String title, int actorUserId,
      String actorName) {
    String who = actorName == null || actorName.isBlank() ? "Someone" : actorName;
    notifyFollowers(projectId, discussionId, actorUserId,
        "New comment on \"" + title + "\"",
        who + " commented on a discussion you follow.");
  }

  public void notifyStatusChange(int projectId, int discussionId, String title, int actorUserId,
      String actorName, String newStatus) {
    String who = actorName == null || actorName.isBlank() ? "Someone" : actorName;
    notifyFollowers(projectId, discussionId, actorUserId,
        "\"" + title + "\" is now " + newStatus,
        who + " set a discussion you follow to " + newStatus + ".");
  }

  private void notifyFollowers(int projectId, int discussionId, int actorUserId, String subject,
      String line) {
    try {
      String link = baseUrl + "/projects/" + projectId + "/discussions/" + discussionId;
      for (int uid : follows.followerIds(projectId, discussionId)) {
        if (uid == actorUserId) continue; // don't notify the person who made the change
        AppUser u = users.findById(uid);
        if (u == null || u.getEmail() == null || u.getEmail().isBlank()) continue;
        email.send(u.getEmail(), subject, line + "\n\n" + link);
      }
    } catch (Exception e) {
      // best-effort: swallow any failure so the triggering action still succeeds
    }
  }
}
