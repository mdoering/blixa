package org.catalogueoflife.editor.bhl;

import java.util.List;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// Read-only BHL search + availability. Any project member may search (it only reads BHL); the
// feature is unavailable (409) when no api key is configured.
@Service
public class BhlService {

  private final BhlProperties props;
  private final BhlClient client;
  private final ProjectService projects;

  public BhlService(BhlProperties props, BhlClient client, ProjectService projects) {
    this.props = props;
    this.client = client;
    this.projects = projects;
  }

  public BhlConfigResponse config(int userId, int projectId) {
    projects.requireRole(userId, projectId);
    return new BhlConfigResponse(props.hasKey());
  }

  public List<BhlItem> publicationSearch(int userId, int projectId, String term) {
    projects.requireRole(userId, projectId);
    requireAvailable();
    if (term == null || term.isBlank()) {
      return List.of();
    }
    return client.publicationSearch(term.trim());
  }

  // All pages of a linked BHL item (browse / jump to a page number).
  public List<BhlPage> itemPages(int userId, int projectId, int itemId) {
    projects.requireRole(userId, projectId);
    requireAvailable();
    return client.itemPages(itemId);
  }

  // Pages of the item where the focal name appears (suggested protologue pages).
  public List<BhlPage> namePagesInItem(int userId, int projectId, int itemId, String name) {
    projects.requireRole(userId, projectId);
    requireAvailable();
    if (name == null || name.isBlank()) {
      return List.of();
    }
    return client.namePagesInItem(name.trim(), itemId);
  }

  private void requireAvailable() {
    if (!props.hasKey()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "BHL is not configured for this server");
    }
  }
}
