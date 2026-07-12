package org.catalogueoflife.editor.mergerecords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.editor.mergerecords.dto.UsageMergeCandidate;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MergeRecordsService {

  private final MergeRecordsMapper merge;
  private final NameUsageMapper usages;
  private final ProjectService projects;

  public MergeRecordsService(MergeRecordsMapper merge, NameUsageMapper usages, ProjectService projects) {
    this.merge = merge;
    this.usages = usages;
    this.projects = projects;
  }

  public List<UsageMergeCandidate> previewUsages(int userId, int projectId, List<Integer> ids) {
    projects.requireRole(userId, projectId); // any member may preview
    List<Integer> distinct = requireAtLeastTwo(ids);
    List<UsageMergeCandidate> out = new ArrayList<>();
    for (int id : distinct.stream().sorted().toList()) {  // ascending id (oldest first)
      NameUsage u = usages.findByIdInProject(projectId, id);
      if (u == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usage not in project: " + id);
      Map<String, Object> raw = merge.usageCounts(projectId, id);
      Map<String, Integer> counts = new LinkedHashMap<>();
      raw.forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
      out.add(new UsageMergeCandidate(u.getId(), u.getAlternativeId(), u.getScientificName(),
          u.getAuthorship(), u.getRank(), u.getStatus() == null ? null : u.getStatus().name(), counts));
    }
    return out;
  }

  static List<Integer> requireAtLeastTwo(List<Integer> ids) {
    List<Integer> distinct = ids == null ? List.of() : ids.stream().distinct().toList();
    if (distinct.size() < 2) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least two records to merge");
    }
    return distinct;
  }

  void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
