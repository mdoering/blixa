package org.catalogueoflife.editor.join;

import java.util.List;
import java.util.regex.Pattern;
import org.catalogueoflife.editor.join.dto.JoinRequestBody;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.publicapi.PublicProjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JoinRequestService {

  private static final Pattern ORCID = Pattern.compile("^\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]$");

  private final JoinRequestMapper joinRequests;
  private final PublicProjectMapper publicProjects;
  private final ProjectService projectService;

  public JoinRequestService(JoinRequestMapper joinRequests, PublicProjectMapper publicProjects,
      ProjectService projectService) {
    this.joinRequests = joinRequests;
    this.publicProjects = publicProjects;
    this.projectService = projectService;
  }

  // Unauthenticated: a visitor on a public project page requests to join by ORCID, no user
  // context available. Idempotent -- a repeat submission from the same ORCID is a silent no-op
  // (see JoinRequestMapper.insertIgnoreDup) rather than a conflict, so retries are harmless.
  @Transactional
  public void request(String idOrAlias, JoinRequestBody body) {
    Project p = resolvePublic(idOrAlias);
    String orcid = body.orcid() == null ? null : body.orcid().trim();
    if (orcid == null || !ORCID.matcher(orcid).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ORCID");
    }
    String name = trimToNull(body.name());
    String message = trimToNull(body.message());
    joinRequests.insertIgnoreDup(new JoinRequest(p.getId(), orcid, name, message));
  }

  public List<JoinRequest> list(int userId, int projectId) {
    projectService.requireOwner(userId, projectId);
    return joinRequests.findByProject(projectId);
  }

  public int count(int userId, int projectId) {
    projectService.requireOwner(userId, projectId);
    return joinRequests.countByProject(projectId);
  }

  @Transactional
  public void dismiss(int userId, int projectId, int id) {
    projectService.requireOwner(userId, projectId);
    if (joinRequests.delete(projectId, id) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "join request not found");
    }
  }

  // Mirrors PublicController.resolve(): numeric id-or-alias lookup restricted to is_public=true
  // projects, 404ing rather than leaking whether a matching private project exists.
  private Project resolvePublic(String idOrAlias) {
    Project p = idOrAlias.matches("\\d+")
        ? publicProjects.findPublicById(Integer.parseInt(idOrAlias))
        : publicProjects.findPublicByAlias(idOrAlias);
    if (p == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
    }
    return p;
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
