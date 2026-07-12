package org.catalogueoflife.editor.release;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReleaseMetricsService {

  private static final List<String> SYNONYM_STATUSES = List.of("SYNONYM", "MISAPPLIED");

  private final ReleaseMetricsMapper m;
  private final ObjectMapper json;

  public ReleaseMetricsService(ReleaseMetricsMapper m, ObjectMapper json) {
    this.m = m;
    this.json = json;
  }

  // Returns the metrics snapshot JSON. `since` is the previous READY release's created_at (null for
  // the first release -> counts all changes).
  public String compute(int projectId, OffsetDateTime since) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("acceptedByRank", byRank(projectId, List.of("ACCEPTED")));
    out.put("synonymsByRank", byRank(projectId, SYNONYM_STATUSES));
    Map<String, Integer> supp = new LinkedHashMap<>();
    supp.put("vernacular", m.vernacular(projectId));
    supp.put("distribution", m.distribution(projectId));
    supp.put("media", m.media(projectId));
    supp.put("typeMaterial", m.typeMaterial(projectId));
    supp.put("nameRelation", m.nameRelation(projectId));
    supp.put("property", m.property(projectId));
    supp.put("estimate", m.estimate(projectId));
    supp.put("reference", m.reference(projectId));
    out.put("supplementary", supp);
    out.put("changesSinceLastRelease", m.changesSince(projectId, since));
    out.put("contributions", m.contributionsSince(projectId, since).stream().map(row -> {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put("userId", row.get("userId"));
      c.put("name", row.get("name"));
      c.put("orcid", row.get("orcid"));
      c.put("count", ((Number) row.get("cnt")).intValue());
      return c;
    }).toList());
    return json.writeValueAsString(out);
  }

  private Map<String, Integer> byRank(int projectId, List<String> statuses) {
    Map<String, Integer> map = new LinkedHashMap<>();
    for (Map<String, Object> row : m.countByRank(projectId, statuses)) {
      map.put(String.valueOf(row.get("rank")), ((Number) row.get("cnt")).intValue());
    }
    return map;
  }
}
