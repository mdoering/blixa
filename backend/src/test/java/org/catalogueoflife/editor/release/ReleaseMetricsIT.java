package org.catalogueoflife.editor.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.catalogueoflife.editor.support.AbstractPostgresIT;
import org.catalogueoflife.editor.name.dto.CreateNameUsageRequest;
import org.catalogueoflife.editor.name.NameUsageService;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.dto.CreateProjectRequest;
import org.catalogueoflife.editor.user.AppUser;
import org.catalogueoflife.editor.user.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ReleaseMetricsIT extends AbstractPostgresIT {

  @Autowired ReleaseMetricsService metrics;
  @Autowired ProjectService projects;
  @Autowired NameUsageService usages;
  @Autowired AppUserService users;
  @Autowired ObjectMapper json;

  @Test
  void computesRankBreakdownAndChangeCounts() throws Exception {
    AppUser u = users.createLocal("mUser", "pw", "M User");
    var p = projects.create(u.getId(), new CreateProjectRequest("Metrics", "zoological"));
    int pid = p.getId();
    // 1 accepted genus + 1 accepted species + 1 synonym species
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus", null, "genus", "ACCEPTED",
        null, null, null, null, null, null, null, null, null, null, null, null, null));
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus bus", null, "species", "ACCEPTED",
        null, null, null, null, null, null, null, null, null, null, null, null, null));
    usages.create(u.getId(), pid, new CreateNameUsageRequest("Aus cus", null, "species", "SYNONYM",
        null, null, null, null, null, null, null, null, null, null, null, null, null));

    JsonNode m = json.readTree(metrics.compute(pid, null));
    assertThat(m.get("acceptedByRank").get("genus").asInt()).isEqualTo(1);
    assertThat(m.get("acceptedByRank").get("species").asInt()).isEqualTo(1);
    assertThat(m.get("synonymsByRank").get("species").asInt()).isEqualTo(1);
    assertThat(m.get("supplementary").get("reference").asInt()).isEqualTo(0);
    // 3 create changes since project start (since = null)
    assertThat(m.get("changesSinceLastRelease").asInt()).isGreaterThanOrEqualTo(3);
    // one contributor with >=3 edits
    assertThat(m.get("contributions").get(0).get("count").asInt()).isGreaterThanOrEqualTo(3);
    assertThat(m.get("contributions").get(0).get("name").asString()).isEqualTo("M User");
  }
}
