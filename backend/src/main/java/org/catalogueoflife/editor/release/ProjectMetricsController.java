package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;
import org.catalogueoflife.editor.auth.CurrentUser;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Live "now" metrics for a project (shown at the top of the Releases tab): the same snapshot a
// release would capture, computed against the current data with the last READY release as the
// change boundary. Any project member may read.
@RestController
public class ProjectMetricsController {

  private final CurrentUser currentUser;
  private final ProjectService projects;
  private final ReleaseMetricsService metrics;
  private final ReleaseMapper releases;
  private final ObjectMapper json;

  public ProjectMetricsController(CurrentUser currentUser, ProjectService projects,
      ReleaseMetricsService metrics, ReleaseMapper releases, ObjectMapper json) {
    this.currentUser = currentUser;
    this.projects = projects;
    this.metrics = metrics;
    this.releases = releases;
    this.json = json;
  }

  @GetMapping("/api/projects/{pid}/metrics")
  public JsonNode metrics(@PathVariable int pid) {
    int uid = currentUser.require().getId();
    projects.requireRole(uid, pid);
    Release latest = releases.findLatestReady(pid);
    OffsetDateTime since = latest == null ? null : latest.getCreatedAt();
    // compute() returns a JSON string; parse it so the endpoint emits a JSON object, not a quoted
    // string.
    return json.readTree(metrics.compute(pid, since));
  }
}
