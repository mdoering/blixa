package org.catalogueoflife.editor.discussion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Manages a per-project API token and the token-gated external submission path (Phase 4). External
// submissions arrive as REVIEW discussions for editors to triage. The token is a shared secret
// stored as-is (an internal-tool tradeoff) so editors can view + copy it into the COL integration.
@Service
public class DiscussionApiTokenService {

  private final DiscussionApiTokenMapper tokens;
  private final ProjectService projects;
  private final DiscussionService discussions;
  private final SecureRandom random = new SecureRandom();

  public DiscussionApiTokenService(DiscussionApiTokenMapper tokens, ProjectService projects,
      DiscussionService discussions) {
    this.tokens = tokens;
    this.projects = projects;
    this.discussions = discussions;
  }

  public String get(int userId, int projectId) {
    requireEditor(userId, projectId);
    return tokens.findToken(projectId);
  }

  @Transactional
  public String generate(int userId, int projectId) {
    requireEditor(userId, projectId);
    String token = newToken();
    tokens.upsert(projectId, token);
    return token;
  }

  @Transactional
  public void revoke(int userId, int projectId) {
    requireEditor(userId, projectId);
    tokens.delete(projectId);
  }

  // External (no session): the token must match the project's, else 401. Creates a REVIEW discussion.
  @Transactional
  public Discussion submitExternal(int projectId, String token, String title, String body,
      String authorOrcid) {
    String expected = tokens.findToken(projectId);
    if (expected == null || token == null
        || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid api token");
    }
    if (title == null || title.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
    }
    return discussions.createExternal(projectId, title.trim(), body, authorOrcid);
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "editor required");
    }
  }

  private String newToken() {
    byte[] b = new byte[24];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
